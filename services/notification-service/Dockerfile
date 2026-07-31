# FINIX Notification Service
# Build from repo root:
#   docker build -f services/notification-service/Dockerfile -t finix-notification-service .

FROM node:20-alpine AS deps
WORKDIR /app
COPY services/notification-service/package.json ./
RUN npm install --omit=dev

FROM node:20-alpine
WORKDIR /app
RUN adduser -D -H -u 10001 finix
COPY --from=deps /app/node_modules ./node_modules
COPY services/notification-service/package.json ./
COPY services/notification-service/src ./src
USER finix
ENV PORT=8093
ENV NODE_ENV=production
EXPOSE 8093
CMD ["node", "src/index.js"]
