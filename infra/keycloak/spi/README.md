# FINIX Keycloak Adaptive Authenticator SPI

Builds a provider JAR that Keycloak loads from `/opt/keycloak/providers`.

```bash
cd infra/keycloak/spi
gradle jar   # or use a local gradle; output: build/libs/finix-adaptive-auth-0.1.0.jar
```

Compose mounts the JAR when present:

```yaml
volumes:
  - ../keycloak/spi/build/libs/finix-adaptive-auth-0.1.0.jar:/opt/keycloak/providers/finix-adaptive-auth.jar:ro
```

The authenticator calls `POST /api/v1/auth/login-risk` on identity-service and sets auth note
`finix.requireStepUp=true` when the score requires MFA. Wire it into the browser flow in the
Keycloak admin console (Authentication → Flows) after Username/Password and before OTP/WebAuthn.
