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

# Production stage
FROM amazonlinux:2023

# Install packages
RUN yum update -y && yum install -y nginx \
    && yum clean all

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# implement changes required to run NGINX as an unprivileged user
RUN rm /etc/nginx/nginx.conf.default && \
    sed -i '/access_log.*main/d' /etc/nginx/nginx.conf && \
    sed -i 's,listen       80;,listen       8080;,' /etc/nginx/nginx.conf && \
    sed -i 's,listen       \[::\]:80;,listen       \[::\]:8080;,' /etc/nginx/nginx.conf && \
    sed -i '/user  nginx;/d' /etc/nginx/nginx.conf && \
    sed -i 's,/var/run/nginx.pid,/tmp/nginx.pid,' /etc/nginx/nginx.conf && \
    sed -i "/^http {/a \    proxy_temp_path /tmp/proxy_temp;\n    client_body_temp_path /tmp/client_temp;\n    fastcgi_temp_path /tmp/fastcgi_temp;\n    uwsgi_temp_path /tmp/uwsgi_temp;\n    scgi_temp_path /tmp/scgi_temp;\n" /etc/nginx/nginx.conf 
# nginx user must own the cache and etc directory to write cache and tweak the nginx config
RUN mkdir -p /var/cache/nginx /var/log/nginx && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx && \
    chmod -R g+w /var/cache/nginx /var/log/nginx /etc/nginx 


# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

EXPOSE 5173
# Switch to the non-root user
USER nginx:nginx
# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
