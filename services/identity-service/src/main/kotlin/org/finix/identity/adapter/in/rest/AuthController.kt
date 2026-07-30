package org.finix.identity.adapter.`in`.rest

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletResponse
import org.finix.identity.application.usecase.ScoreLoginRiskUseCase
import org.finix.identity.config.KeycloakProperties
import org.finix.identity.domain.RiskScore
import org.finix.kernel.domain.DomainError
import org.finix.kernel.domain.DomainException
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import java.time.Duration

/**
 * BFF auth surface: PKCE authorize URL, code→token exchange into httpOnly cookies, logout,
 * and the login-risk endpoint the Keycloak adaptive SPI calls.
 *
 * Access and refresh tokens never enter `localStorage` — the browser only ever sees cookies.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val keycloak: KeycloakProperties,
    private val restClientBuilder: RestClient.Builder,
    private val scoreLoginRisk: ScoreLoginRiskUseCase,
) {
    @GetMapping("/authorize")
    fun authorize(
        @RequestParam("redirect_uri") redirectUri: String,
        @RequestParam state: String,
        @RequestParam("code_challenge") codeChallenge: String,
        @RequestParam("code_challenge_method") codeChallengeMethod: String,
    ): AuthorizeResponse {
        if (codeChallengeMethod != S256) {
            DomainError.Invalid(
                detail = "only S256 PKCE is supported",
                properties = mapOf("code_challenge_method" to codeChallengeMethod),
            ).raise()
        }
        val url = UriComponentsBuilder
            .fromUriString(keycloak.authorizationEndpoint())
            .queryParam("client_id", keycloak.clientId)
            .queryParam("response_type", "code")
            .queryParam("scope", "openid profile email")
            .queryParam("redirect_uri", redirectUri)
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", codeChallengeMethod)
            .build()
            .toUriString()
        return AuthorizeResponse(authorizationUrl = url)
    }

    @PostMapping("/token")
    fun token(
        @RequestBody body: TokenRequest,
        response: HttpServletResponse,
    ): ResponseEntity<TokenAcceptedResponse> {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", keycloak.clientId)
            add("code", body.code)
            add("redirect_uri", body.redirectUri)
            add("code_verifier", body.codeVerifier)
        }

        val tokens = try {
            restClientBuilder.build()
                .post()
                .uri(keycloak.tokenEndpoint())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(KeycloakTokenResponse::class.java)
                ?: DomainError.Unavailable("keycloak", "empty token response").raise()
        } catch (ex: RestClientResponseException) {
            throw DomainException(
                DomainError.Unavailable(
                    dependency = "keycloak",
                    detail = "token exchange failed with HTTP ${ex.statusCode.value()}",
                ),
                ex,
            )
        }

        writeCookie(
            response,
            sessionCookie(ACCESS_COOKIE, tokens.accessToken, Duration.ofSeconds(tokens.expiresIn.toLong())),
        )
        writeCookie(
            response,
            sessionCookie(
                REFRESH_COOKIE,
                tokens.refreshToken.orEmpty(),
                Duration.ofDays(REFRESH_COOKIE_DAYS),
            ),
        )

        return ResponseEntity.ok(
            TokenAcceptedResponse(tokenType = tokens.tokenType, expiresIn = tokens.expiresIn),
        )
    }

    @PostMapping("/logout")
    fun logout(response: HttpServletResponse): ResponseEntity<Map<String, String>> {
        writeCookie(response, clearCookie(ACCESS_COOKIE))
        writeCookie(response, clearCookie(REFRESH_COOKIE))
        return ResponseEntity.ok(mapOf("status" to "logged_out"))
    }

    @PostMapping("/login-risk")
    fun loginRisk(@RequestBody body: LoginRiskRequest): RiskScoreResponse {
        val score = scoreLoginRisk.execute(body.keycloakUserId, body.fingerprint, body.ip)
        return RiskScoreResponse.from(score)
    }

    private fun writeCookie(response: HttpServletResponse, cookie: ResponseCookie) {
        response.addHeader("Set-Cookie", cookie.toString())
    }

    private fun sessionCookie(name: String, value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(false) // local HTTP demo; TLS terminates at the gateway in compose
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()

    private fun clearCookie(name: String): ResponseCookie =
        ResponseCookie.from(name, "")
            .httpOnly(true)
            .secure(false)
            .sameSite("Strict")
            .path("/")
            .maxAge(Duration.ZERO)
            .build()

    companion object {
        const val ACCESS_COOKIE = "finix_access"
        const val REFRESH_COOKIE = "finix_refresh"
        private const val S256 = "S256"
        private const val REFRESH_COOKIE_DAYS = 30L
    }
}

data class AuthorizeResponse(val authorizationUrl: String)

data class TokenRequest(
    val code: String,
    @param:JsonProperty("redirect_uri")
    val redirectUri: String,
    @param:JsonProperty("code_verifier")
    val codeVerifier: String,
)

data class LoginRiskRequest(
    val keycloakUserId: String,
    val fingerprint: String,
    val ip: String,
)

data class RiskScoreResponse(
    val score: Int,
    val requireStepUp: Boolean,
) {
    companion object {
        fun from(score: RiskScore) = RiskScoreResponse(score.score, score.requireStepUp)
    }
}

data class TokenAcceptedResponse(
    val tokenType: String,
    val expiresIn: Int,
)

data class KeycloakTokenResponse(
    @param:JsonProperty("access_token")
    val accessToken: String,
    @param:JsonProperty("refresh_token")
    val refreshToken: String? = null,
    @param:JsonProperty("token_type")
    val tokenType: String = "Bearer",
    @param:JsonProperty("expires_in")
    val expiresIn: Int = 300,
)
