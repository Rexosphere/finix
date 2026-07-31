package org.finix.keycloak;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Adaptive authenticator: asks FINIX identity/risk whether this login needs step-up MFA.
 *
 * Configured via authenticator config key {@code finixLoginRiskUrl}
 * (default {@code http://identity-service:8082/api/v1/auth/login-risk}).
 */
public class AdaptiveRiskAuthenticator implements Authenticator {

    public static final String LOGIN_RISK_URL = "finixLoginRiskUrl";
    public static final String REQUIRE_STEP_UP_NOTE = "finix.requireStepUp";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        if (user == null) {
            context.attempted();
            return;
        }

        String url = context.getAuthenticatorConfig() != null
            ? context.getAuthenticatorConfig().getConfig().getOrDefault(
                LOGIN_RISK_URL,
                "http://identity-service:8082/api/v1/auth/login-risk")
            : "http://identity-service:8082/api/v1/auth/login-risk";

        String fingerprint = context.getHttpRequest().getHttpHeaders().getHeaderString("X-Device-Fingerprint");
        if (fingerprint == null || fingerprint.isBlank()) {
            fingerprint = "unknown-" + context.getConnection().getRemoteAddr();
        }
        String ip = context.getConnection().getRemoteAddr();

        boolean requireStepUp = callLoginRisk(url, user.getId(), fingerprint, ip);
        context.getAuthenticationSession().setAuthNote(REQUIRE_STEP_UP_NOTE, Boolean.toString(requireStepUp));

        if (requireStepUp) {
            // Continue the flow; subsequent OTP/WebAuthn executions should be conditional on this note
            // in the realm authentication flow configuration.
            context.getAuthenticationSession().setAuthNote("finix.stepUpReason", "login-risk");
        }
        context.success();
    }

    private boolean callLoginRisk(String url, String keycloakUserId, String fingerprint, String ip) {
        try {
            String json = """
                {"keycloakUserId":"%s","fingerprint":"%s","ip":"%s"}
                """.formatted(escape(keycloakUserId), escape(fingerprint), escape(ip));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                // Fail closed to step-up when risk is unreachable.
                return true;
            }
            String body = response.body();
            return body.contains("\"requireStepUp\":true") || body.contains("\"require_step_up\":true");
        } catch (Exception ex) {
            return true;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // no-op — decision is made in authenticate()
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // OTP / WebAuthn required actions are attached by the realm flow when step-up is required.
    }

    @Override
    public void close() {
        // nothing
    }
}
