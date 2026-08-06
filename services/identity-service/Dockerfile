# Base pinned by digest so a rebuild of an old commit produces the same image.
# Dependabot (docker ecosystem) proposes digest bumps; nothing floats silently.
FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c

# Runs as an unprivileged uid: the jar needs no write access outside /tmp.
RUN addgroup -S finix && adduser -S -u 10001 -G finix finix
WORKDIR /app
COPY --chown=10001:10001 services/identity-service/build/libs/app.jar app.jar
USER 10001:10001

EXPOSE 8082
# Compose and any orchestrator inherit this, so liveness is defined once, next
# to the service, instead of being repeated per environment.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8082/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-XX:+UseSerialGC","-Xms64m","-Xmx192m","-jar","app.jar"]
