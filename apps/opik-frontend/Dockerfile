# Build stage
FROM node:26.13.0-alpine3.23@sha256:e6c03d42b46af3b1e98207e59f06a43cfe5efd3ec4b4d3d9802bddf04f833374 AS builder

WORKDIR /opt/frontend

COPY package*.json ./
RUN npm ci
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
ARG BUILD_PLUGINS
ENV VITE_FE_PLUGINS=${BUILD_PLUGINS}
RUN npm run build -- --mode $BUILD_MODE \
    && printf '{"version": "%s"}\n' "$VITE_APP_VERSION" > dist/version.json

# Production stage
# Slim plus only the OpenTelemetry module: the -otel image is built on the full alpine variant, whose other
# modules we don't use and which bring in brotli and expat CVEs. The module comes from nginx.org, signed with the
# key slim already trusts, pinned the way nginx's own -otel Dockerfile pins it. curl is for the compose healthcheck.
FROM nginx:1.30.6-alpine-slim@sha256:e5249ed1542e4274cd633f034176469bd7384a4be78d991e77c2b709e8066de4 AS nginx
ARG OTEL_VERSION=0.1.2
# curl stays unpinned: Alpine keeps only the latest build in a release's repo, so a pin breaks the next rebuild.
# hadolint ignore=DL3018
RUN apk add --no-cache -X "https://nginx.org/packages/alpine/v$(grep -oE '^[0-9]+\.[0-9]+' /etc/alpine-release)/main" \
    "nginx-module-otel=${NGINX_VERSION}.${OTEL_VERSION}-r${PKG_RELEASE}" curl

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Copy custom Nginx configuration
COPY --chown=1001:1001 nginx /etc/nginx

# Copy entrypoint script to the docker-entrypoint.d directory so it runs automatically
COPY --chmod=0755 patch-nginx.conf.sh /docker-entrypoint.d/99-patch-nginx.conf.sh
      
RUN chown -R 1001:1001 /var/log/nginx /var/cache/nginx /run /etc/nginx/conf.d \
 && rm -f /var/log/nginx/access.log /var/log/nginx/error.log

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

EXPOSE 5173

USER 1001:1001
