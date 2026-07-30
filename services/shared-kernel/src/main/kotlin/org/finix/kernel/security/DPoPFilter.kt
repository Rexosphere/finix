package org.finix.kernel.security

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.Ed25519Verifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.OctetKeyPair
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.SignedJWT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.finix.kernel.crypto.Hashing
import org.finix.kernel.web.CorrelationContext
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.text.ParseException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * **RFC 9449 DPoP** proof-of-possession for access tokens.
 *
 * A stolen bearer token is useless without the private key that signed the `DPoP` proof.
 * When a `DPoP` header is present (or when [DPoPProperties.required] is true for authenticated
 * requests), this filter verifies signature, `htm`/`htu`, `ath`, freshness, and `jti` uniqueness.
 * If the access token carries `cnf.jkt`, the proof key thumbprint must match.
 */
class DPoPFilter(
    private val properties: DPoPProperties,
    private val replayStore: DPoPReplayStore = InMemoryDPoPReplayStore(),
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!properties.enabled) return true
        val path = request.requestURI
        return properties.excludePathPrefixes.any { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val proofHeader = request.getHeader(HEADER_DPOP)
        val auth = SecurityContextHolder.getContext().authentication

        if (proofHeader.isNullOrBlank()) {
            if (properties.required && auth is JwtAuthenticationToken) {
                reject(response, "dpop-required", "DPoP proof is required for this request")
                return
            }
            filterChain.doFilter(request, response)
            return
        }

        if (auth !is JwtAuthenticationToken) {
            reject(response, "dpop-unauthenticated", "DPoP proof requires a bearer access token")
            return
        }

        try {
            verify(proofHeader, auth.token.tokenValue, request, auth)
            filterChain.doFilter(request, response)
        } catch (ex: DPoPException) {
            log.debug { "DPoP rejected: ${ex.code} — ${ex.message}" }
            reject(response, ex.code, ex.message ?: "Invalid DPoP proof")
        }
    }

    private fun verify(
        proofCompact: String,
        accessToken: String,
        request: HttpServletRequest,
        auth: JwtAuthenticationToken,
    ) {
        val jwt = parseProof(proofCompact)
        requireTyp(jwt)
        val jwk = jwt.header.jwk
            ?: throw DPoPException("dpop-jwk", "DPoP JWT must embed a public JWK")
        if (!verifySignature(jwt, jwk)) {
            throw DPoPException("dpop-signature", "DPoP proof signature is invalid")
        }
        verifyBindingClaims(jwt, accessToken, request)
        verifyKeyThumbprint(jwk, auth)
    }

    private fun parseProof(proofCompact: String): SignedJWT = try {
        SignedJWT.parse(proofCompact)
    } catch (ex: ParseException) {
        throw DPoPException("dpop-malformed", "DPoP proof is not a valid JWT", ex)
    }

    private fun requireTyp(jwt: SignedJWT) {
        if (jwt.header.type?.type != "dpop+jwt") {
            throw DPoPException("dpop-type", "DPoP JWT typ must be dpop+jwt")
        }
    }

    private fun verifyBindingClaims(jwt: SignedJWT, accessToken: String, request: HttpServletRequest) {
        val claims = jwt.jwtClaimsSet
        val htm = requiredClaim(claims.getStringClaim("htm"), "dpop-htm", "DPoP claim htm is required")
        ensure(htm.equals(request.method, ignoreCase = true), "dpop-htm", "DPoP htm does not match HTTP method")

        val htu = requiredClaim(claims.getStringClaim("htu"), "dpop-htu", "DPoP claim htu is required")
        ensure(htu == buildHtu(request), "dpop-htu", "DPoP htu does not match request URI")

        val ath = requiredClaim(claims.getStringClaim("ath"), "dpop-ath", "DPoP claim ath is required")
        ensure(constantTimeEquals(ath, accessTokenHash(accessToken)), "dpop-ath", "DPoP ath does not match access token")

        val iat = claims.issueTime?.toInstant()
            ?: throw DPoPException("dpop-iat", "DPoP claim iat is required")
        val age = Instant.now().epochSecond - iat.epochSecond
        ensure(
            age >= -properties.clockSkewSeconds && age <= properties.maxAgeSeconds,
            "dpop-iat",
            "DPoP proof is outside the accepted time window",
        )

        val jti = requiredClaim(claims.jwtid, "dpop-jti", "DPoP claim jti is required")
        ensure(replayStore.tryRecord(jti, properties.maxAgeSeconds), "dpop-replay", "DPoP proof jti has already been used")
    }

    private fun requiredClaim(value: String?, code: String, detail: String): String =
        value ?: throw DPoPException(code, detail)

    private fun ensure(condition: Boolean, code: String, detail: String) {
        if (!condition) throw DPoPException(code, detail)
    }

    private fun verifyKeyThumbprint(jwk: JWK, auth: JwtAuthenticationToken) {
        @Suppress("UNCHECKED_CAST")
        val cnf = auth.token.claims["cnf"] as? Map<*, *>
        val tokenJkt = cnf?.get("jkt") as? String ?: return
        val proofJkt = jwk.computeThumbprint().toString()
        if (tokenJkt != proofJkt) {
            throw DPoPException("dpop-jkt", "DPoP proof key does not match token cnf.jkt")
        }
    }

    private fun verifySignature(jwt: SignedJWT, jwk: JWK): Boolean {
        val verifier = when (jwk) {
            is RSAKey -> RSASSAVerifier(jwk.toRSAPublicKey())
            is ECKey -> ECDSAVerifier(jwk.toECPublicKey())
            is OctetKeyPair -> Ed25519Verifier(jwk)
            else -> throw DPoPException("dpop-alg", "Unsupported DPoP JWK type ${jwk.keyType}")
        }
        val alg = jwt.header.algorithm
        if (alg !in ALLOWED_ALGS) {
            throw DPoPException("dpop-alg", "DPoP algorithm $alg is not allowed")
        }
        return jwt.verify(verifier)
    }

    /** RFC 9449 htu: scheme://authority/path — no query, no fragment. */
    internal fun buildHtu(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host")
            ?: request.getHeader("Host")
            ?: "${request.serverName}:${request.serverPort}"
        return "$scheme://$host${request.requestURI}"
    }

    private fun accessTokenHash(accessToken: String): String {
        val digest = Hashing.sha256(accessToken.toByteArray(StandardCharsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        Hashing.constantTimeEquals(
            a.toByteArray(StandardCharsets.US_ASCII),
            b.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun reject(response: HttpServletResponse, code: String, detail: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.setHeader("WWW-Authenticate", "DPoP error=\"invalid_dpop_proof\"")
        val safe = detail.replace("\"", "'")
        val trace = CorrelationContext.traceId()
        response.writer.write(
            """{"type":"https://finix.lk/problems/$code","title":"DPoP proof rejected",""" +
                """"status":401,"detail":"$safe","instance":"urn:finix:trace:$trace","code":"$code"}""",
        )
    }

    companion object {
        const val HEADER_DPOP: String = "DPoP"
        private val ALLOWED_ALGS = setOf(
            JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512,
            JWSAlgorithm.ES256, JWSAlgorithm.ES384, JWSAlgorithm.ES512,
            JWSAlgorithm.EdDSA,
            JWSAlgorithm.PS256, JWSAlgorithm.PS384, JWSAlgorithm.PS512,
        )
    }
}

class DPoPException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

fun interface DPoPReplayStore {
    /** Returns true if [jti] was newly recorded, false if it is a replay. */
    fun tryRecord(jti: String, ttlSeconds: Long): Boolean
}

class InMemoryDPoPReplayStore : DPoPReplayStore {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun tryRecord(jti: String, ttlSeconds: Long): Boolean {
        prune()
        val expiresAt = System.currentTimeMillis() + ttlSeconds * MILLIS_PER_SECOND
        return seen.putIfAbsent(jti, expiresAt) == null
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        seen.entries.removeIf { it.value < now }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Visible for tests: base64url SHA-256 of an access token, matching RFC 9449 `ath`. */
fun dpopAccessTokenHash(accessToken: String): String {
    val digest = Hashing.sha256(accessToken.toByteArray(StandardCharsets.US_ASCII))
    return Base64URL.encode(digest).toString()
}
