# Build stage
FROM node:20.19.6-alpine3.23 AS builder

WORKDIR /opt/frontend

COPY package*.json ./
COPY patches ./patches
RUN npm install
# Copy and build the application
COPY . .

ARG OPIK_VERSION
ARG SENTRY_ENABLED
ARG SENTRY_DSN

ENV VITE_APP_VERSION=${OPIK_VERSION}
ENV VITE_SENTRY_ENABLED=${SENTRY_ENABLED}
ENV VITE_SENTRY_DSN=${SENTRY_DSN}
ENV NODE_OPTIONS="--max-old-space-size=8192"

ARG BUILD_MODE=production
RUN npm run build -- --mode $BUILD_MODE

# Create version.json for checking version
RUN printf '{"version": "%s"}\n' "$VITE_APP_VERSION" > dist/version.json

# Production stage
# Using nginx alpine image with OpenTelemetry support
FROM nginx:1.29.4-alpine-otel AS nginx

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Copy custom Nginx configuration
COPY --chown=nginx:nginx nginx /etc/nginx

# Copy entrypoint script to the docker-entrypoint.d directory so it runs automatically
COPY --chmod=0755 patch-nginx.conf.sh /docker-entrypoint.d/99-patch-nginx.conf.sh
      
# Fix permissions
RUN chown -R nginx:nginx /var/log/nginx /var/cache/nginx /run /etc/nginx/conf.d \
 && rm -f /var/log/nginx/access.log /var/log/nginx/error.log

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

EXPOSE 5173

USER nginx:nginx
