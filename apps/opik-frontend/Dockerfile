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
# Add nginx repo to get specific version
RUN yum update -y && \
    yum install -y nginx-1.26.3 && \
    yum clean all

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# implement changes required to run NGINX as an unprivileged user
RUN rm -f /etc/nginx/nginx.conf.default && \
    sed -i '/access_log.*main/d' /etc/nginx/nginx.conf && \
    sed -i 's,listen       80;,listen       8080;,' /etc/nginx/nginx.conf && \
    sed -i 's,listen       \[::\]:80;,listen       \[::\]:8080;,' /etc/nginx/nginx.conf && \
    sed -i '/user nginx/d' /etc/nginx/nginx.conf && \
    sed -i 's/error_log\ \/var\/log\/nginx\/error.log/error_log\ \/dev\/stderr/' /etc/nginx/nginx.conf && \
    sed -i 's/access_log\ \/var\/log\/nginx\/access.log/access_log\ \/dev\/stdout/' /etc/nginx/nginx.conf
RUN mkdir -p /var/cache/nginx /var/log/nginx /run && \
    chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx /run /var/run && \
    chmod -R g+w /var/cache/nginx /var/log/nginx /etc/nginx /run /var/run

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

EXPOSE 5173

USER nginx:nginx
# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
