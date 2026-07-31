package org.finix.keycloak;

import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.List;

public class AdaptiveRiskAuthenticatorFactory implements AuthenticatorFactory {

    public static final String ID = "finix-adaptive-risk";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "FINIX Adaptive Login Risk";
    }

    @Override
    public String getHelpText() {
        return "Calls FINIX identity login-risk and sets finix.requireStepUp for conditional MFA.";
    }

    @Override
    public String getReferenceCategory() {
        return "mfa";
    }

    @Override
    public boolean isConfigurable() {
        return true;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return new AuthenticationExecutionModel.Requirement[] {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.DISABLED,
        };
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        ProviderConfigProperty url = new ProviderConfigProperty();
        url.setName(AdaptiveRiskAuthenticator.LOGIN_RISK_URL);
        url.setLabel("Login risk URL");
        url.setType(ProviderConfigProperty.STRING_TYPE);
        url.setDefaultValue("http://identity-service:8082/api/v1/auth/login-risk");
        return List.of(url);
    }

    @Override
    public Authenticator create(KeycloakSession session) {
        return new AdaptiveRiskAuthenticator();
    }

    @Override
    public void init(Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}
