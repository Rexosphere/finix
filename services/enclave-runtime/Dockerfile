# Hardened reconstruct-only image for FINIX enclave-runtime.
#
# Intended compose / k8s runtime constraints (not enforceable in the image alone):
#   - read-only root filesystem
#   - tmpfs mount at /tmp (and /dev/shm if needed); no persistent volumes
#   - no privileged mode, no host PID/network
#   - drop all capabilities; runs as an unprivileged uid below
#
# Do not add shells, curl, package managers, or debug aids to this image. The
# HEALTHCHECK installs nothing: it calls the busybox wget the base already
# ships, against the only endpoint this service exposes.
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

RUN addgroup -S finix && adduser -S -u 10001 -G finix finix
WORKDIR /app
COPY --chown=10001:10001 services/enclave-runtime/build/libs/app.jar app.jar
USER 10001:10001

EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8090/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java","-XX:+UseSerialGC","-Xms32m","-Xmx96m","-jar","app.jar"]
