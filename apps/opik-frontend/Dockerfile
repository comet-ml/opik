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

# Copy custom nginx.conf with OpenTelemetry and dual logging configuration
COPY --chown=nginx:nginx --chmod=0755 nginx /etc/nginx
# Copy entrypoint script for conditional OpenTelemetry configuration
COPY --chown=nginx:nginx --chmod=0755 entrypoint.sh /docker-entrypoint.d/opik-frontend-entrypoint.sh

# Install gettext for envsubst (needed for environment variable substitution)
# RUN apk add --no-cache gettext

# Setup directories and permissions for unprivileged user
# Remove any existing symlinks to stdout/stderr and create actual log files
RUN chown -R nginx:nginx /var/log/nginx /var/cache/nginx /run \
 && rm -f /var/log/nginx/access.log /var/log/nginx/error.log

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

# Expose port (default 8080, can be overridden via NGINX_PORT env var)
EXPOSE 8080

USER nginx:nginx

# Use entrypoint script to conditionally configure OpenTelemetry
# The entrypoint script uses envsubst to generate otel.conf from template
# Note: access_log is configured to write to both file and stdout in nginx.conf
# ENTRYPOINT ["/entrypoint.sh"]
