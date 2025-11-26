# Build stage
FROM node:20.19.5-alpine3.22 AS builder

WORKDIR /opt/frontend

COPY package*.json ./
COPY patches ./patches
RUN npm install
# Copy and build the application
COPY --exclude=nginx . .

ARG OPIK_VERSION
ARG SENTRY_ENABLED
ARG SENTRY_DSN

ENV VITE_APP_VERSION=${OPIK_VERSION}
ENV VITE_SENTRY_ENABLED=${SENTRY_ENABLED}
ENV VITE_SENTRY_DSN=${SENTRY_DSN}
ENV NODE_OPTIONS="--max-old-space-size=8192"

ARG BUILD_MODE=production
RUN npm run build -- --mode $BUILD_MODE

# Production stage
# Using nginx alpine image with OpenTelemetry support
FROM nginx:1.29.3-alpine-otel AS nginx

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Upgrade system packages to fix vulnerabilities
RUN apk upgrade --no-cache

# Copy custom Nginx configuration
COPY --chown=nginx:nginx --chmod=0755 nginx /etc/nginx

# Copy entrypoint script to the docker-entrypoint.d directory so it runs automatically
COPY --chmod=0755 entrypoint.sh /docker-entrypoint.d/05-opik-frontend-entrypoint.sh

# Fix permissions
RUN chown -R nginx:nginx /var/log/nginx /var/cache/nginx /run /etc/nginx/conf.d \
 && rm -f /var/log/nginx/access.log /var/log/nginx/error.log

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

EXPOSE 8080

USER nginx:nginx
