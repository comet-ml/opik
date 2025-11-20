# Build stage
FROM node:20.19.5-alpine3.22 AS builder

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

# Production stage
FROM nginx:1.28.0-bookworm


# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Copy and install Fluent Bit using installer from builder stage
RUN if [ "$BUILD_MODE" != "comet" ]; then \
apt-get update && apt-get install -y gpg && apt-get clean \
      # Install packages for NGINX and Fluent Bit \
      curl -sL https://raw.githubusercontent.com/fluent/fluent-bit/master/install.sh | sh && \
      mkdir -p /etc/fluent-bit /var/log/fluent-bit && \
      chown -R nginx:nginx /etc/fluent-bit /var/log/fluent-bit; \
    fi

# implement changes required to run NGINX as an unprivileged user
RUN sed -r -i '/access_log.*main/d \
   ; /listen       \[::\]:80/d \
   ; s~^(\s*error_log\s+)[^\s]+(\s.+)$~\1/dev/stderr\2~' /etc/nginx/nginx.conf \
 && sed -r -i 's~^(\s*listen\s+)80([\s;].*)$~\18080\2~' /etc/nginx/conf.d/default.conf \
 && rm -f /etc/nginx/nginx.conf.default \
 && mkdir -p /var/cache/nginx /run /var/log/nginx \
 && chown -R nginx:nginx /var/cache/nginx /etc/nginx /run /var/run /var/log/nginx \
 && chmod -R g+w /var/cache/nginx /etc/nginx /run /var/run \
 && ln -sf /dev/stderr /var/log/nginx/error.log \
 && ln -sf /dev/stdout /var/log/nginx/access.log

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

# Copy entrypoint script (fluent-bit.conf is mounted via volume)
COPY entrypoint.sh /opt/frontend/entrypoint.sh
RUN chmod a+x /opt/frontend/entrypoint.sh
EXPOSE 5173

USER nginx:nginx

# Use entrypoint script to start nginx and conditionally start Fluent Bit
CMD ["/opt/frontend/entrypoint.sh"]
