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
FROM nginx:1.27.4-alpine3.21

# Add label for later inspection
ARG BUILD_MODE=production
LABEL build.mode="${BUILD_MODE}"

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

RUN sed -i '/access_log.*main/d' /etc/nginx/nginx.conf

EXPOSE 5173

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
