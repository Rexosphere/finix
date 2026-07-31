# Hardened reconstruct-only image for FINIX enclave-runtime.
#
# Intended compose / k8s runtime constraints (not enforceable in the image alone):
#   - read-only root filesystem
#   - tmpfs mount at /tmp (and /dev/shm if needed); no persistent volumes
#   - no privileged mode, no host PID/network
#   - drop all capabilities; USER nobody already set below
#
# Do not add shells, curl, package managers, or debug aids to this image.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY services/enclave-runtime/build/libs/app.jar app.jar
USER nobody
ENTRYPOINT ["java","-XX:+UseSerialGC","-Xms32m","-Xmx96m","-jar","app.jar"]
