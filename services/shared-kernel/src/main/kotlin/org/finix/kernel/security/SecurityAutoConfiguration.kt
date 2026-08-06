package org.finix.kernel.security

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.convert.converter.Converter
import org.springframework.core.env.Environment
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Zero-trust defaults for every FINIX resource server: JWT validation against Keycloak,
 * realm-role → `ROLE_*` mapping, stateless sessions, and optional DPoP sender-constraining.
 *
 * A service that needs a different rule (e.g. the public USSD callback) declares its own
 * [SecurityFilterChain] `@Order(1)` bean; this one remains the fallback.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain::class)
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "finix.security", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(DPoPProperties::class)
@EnableMethodSecurity
class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DPoPReplayStore::class)
    fun dPoPReplayStore(): DPoPReplayStore = InMemoryDPoPReplayStore()

    @Bean
    @ConditionalOnMissingBean
    fun dPoPFilter(properties: DPoPProperties, replayStore: DPoPReplayStore): DPoPFilter =
        DPoPFilter(properties, replayStore)

    @Bean
    @ConditionalOnMissingBean
    fun jwtAuthenticationConverter(): Converter<Jwt, out AbstractAuthenticationToken> {
        val converter = JwtAuthenticationConverter()
        converter.setJwtGrantedAuthoritiesConverter { jwt ->
            val realmAccess = jwt.getClaimAsMap("realm_access")
            @Suppress("UNCHECKED_CAST")
            val roles = (realmAccess?.get("roles") as? Collection<String>).orEmpty()
            roles.map { SimpleGrantedAuthority("ROLE_${it.uppercase()}") }
        }
        return converter
    }

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain::class)
    fun finixSecurityFilterChain(
        http: HttpSecurity,
        dPoPFilter: DPoPFilter,
        jwtAuthenticationConverter: Converter<Jwt, out AbstractAuthenticationToken>,
        environment: Environment,
    ): SecurityFilterChain {
        val permitAll = environment.getProperty(
            "finix.security.permit-all",
            Boolean::class.java,
            false,
        )
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/actuator/prometheus",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                ).permitAll()
                // `/api/v1/admin/seed` is deliberately NOT exempted here. It is a demo fixture,
                // not infrastructure, so it follows the policy below like any other route: open
                // while `permit-all` is on, authenticated the moment it is turned off. A carve-out
                // at this point would sit above that decision and silently survive the estate-wide
                // hardening switch. Guarded by SeedEndpointAuthorizationTest.
                auth.requestMatchers("/api/v1/auth/**").permitAll()
                if (permitAll) {
                    auth.anyRequest().permitAll()
                } else {
                    auth.anyRequest().authenticated()
                }
            }
            .oauth2ResourceServer { oauth ->
                oauth.jwt { jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter) }
            }
            .addFilterAfter(dPoPFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}
