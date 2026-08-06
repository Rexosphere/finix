# Base pinned by digest so a rebuild of an old commit produces the same image.
# Dependabot (docker ecosystem) proposes digest bumps; nothing floats silently.
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

# Runs as an unprivileged uid: the jar needs no write access outside /tmp.
RUN addgroup -S finix && adduser -S -u 10001 -G finix finix
WORKDIR /app
COPY --chown=10001:10001 services/transaction-orchestrator/build/libs/app.jar app.jar
USER 10001:10001

EXPOSE 8085
# Compose and any orchestrator inherit this, so liveness is defined once, next
# to the service, instead of being repeated per environment.
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8085/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-XX:+UseSerialGC","-Xms64m","-Xmx192m","-jar","app.jar"]
