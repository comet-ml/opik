# Build stage
FROM node:20.19.0-alpine3.20 AS builder

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

# Download Fluent Bit installer in builder stage for optimization
RUN apk add --no-cache wget && \
    wget -O /tmp/fluent-bit-install.sh https://raw.githubusercontent.com/fluent/fluent-bit/master/install.sh && \
    chmod +x /tmp/fluent-bit-install.sh

# Production stage
FROM amazonlinux:2023

# Install packages for NGINX and Fluent Bit
RUN yum update -y && \
    yum install -y nginx-1.26.3 && \
    yum clean all

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Copy and install Fluent Bit using installer from builder stage
COPY --from=builder /tmp/fluent-bit-install.sh /tmp/fluent-bit-install.sh
RUN if [ "$BUILD_MODE" != "comet" ]; then \
      /tmp/fluent-bit-install.sh && \
      rm /tmp/fluent-bit-install.sh && \
      mkdir -p /etc/fluent-bit /var/log/fluent-bit && \
      chown -R nginx:nginx /etc/fluent-bit /var/log/fluent-bit; \
    else \
      echo "Skipping Fluent Bit installation"; \
      rm /tmp/fluent-bit-install.sh; \
    fi

# implement changes required to run NGINX as an unprivileged user
RUN rm -f /etc/nginx/nginx.conf.default && \
    sed -i '/access_log.*main/d' /etc/nginx/nginx.conf && \
    sed -i 's,listen       80;,listen       8080;,' /etc/nginx/nginx.conf && \
    sed -i 's,listen       \[::\]:80;,listen       \[::\]:8080;,' /etc/nginx/nginx.conf && \
    sed -i '/user nginx/d' /etc/nginx/nginx.conf && \
    sed -i 's/error_log\ \/var\/log\/nginx\/error.log/error_log\ \/dev\/stderr/' /etc/nginx/nginx.conf
RUN mkdir -p /var/cache/nginx /run && \
    chown -R nginx:nginx /var/cache/nginx /etc/nginx /run /var/run && \
    chmod -R g+w /var/cache/nginx /etc/nginx /run /var/run && \
    mkdir -p /var/log/nginx && \
    ln -sf /dev/stderr /var/log/nginx/error.log && \
    ln -sf /dev/stdout /var/log/nginx/access.log && \
    chown -R nginx:nginx /var/log/nginx

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

# Copy entrypoint script (fluent-bit.conf is mounted via volume)
COPY entrypoint.sh /opt/frontend/entrypoint.sh
RUN chmod u+x /opt/frontend/entrypoint.sh && \
    chown nginx:nginx /opt/frontend/entrypoint.sh

EXPOSE 5173

USER nginx:nginx

# Use entrypoint script to start nginx and conditionally start Fluent Bit
CMD ["/opt/frontend/entrypoint.sh"]
