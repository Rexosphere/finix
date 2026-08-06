# FINIX Notification Service
# Build from repo root:
#   docker build -f services/notification-service/Dockerfile -t finix-notification-service .

FROM node:25-alpine@sha256:bdf2cca6fe3dabd014ea60163eca3f0f7015fbd5c7ee1b0e9ccb4ced6eb02ef4 AS deps
WORKDIR /app
# npm ci (not install) so the image is built from the committed lockfile —
# same dependency tree here, in CI and on a contributor's machine.
COPY services/notification-service/package.json services/notification-service/package-lock.json ./
RUN npm ci --omit=dev

FROM node:25-alpine@sha256:bdf2cca6fe3dabd014ea60163eca3f0f7015fbd5c7ee1b0e9ccb4ced6eb02ef4
WORKDIR /app
RUN adduser -D -H -u 10001 finix
COPY --from=deps /app/node_modules ./node_modules
COPY services/notification-service/package.json ./
COPY services/notification-service/src ./src
USER finix
ENV PORT=8093
ENV NODE_ENV=production
EXPOSE 8093
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=5 \
  CMD wget -q --spider http://127.0.0.1:8093/health || exit 1
CMD ["node", "src/index.js"]
