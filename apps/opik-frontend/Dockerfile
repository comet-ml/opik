# Build stage
FROM node:20.15.0-alpine3.20 AS builder

WORKDIR /opt/frontend

COPY package*.json ./
COPY patches ./patches
RUN npm install

# Copy and build the application
COPY . .

ARG OPIK_VERSION
ARG SENTRY_AUTH_TOKEN

ENV SENTRY_ORG=comet-or
ENV SENTRY_PROJECT=opik-frontend

ENV VITE_APP_VERSION=${OPIK_VERSION}
ENV NODE_OPTIONS="--max-old-space-size=8192"

ARG BUILD_MODE=production
RUN npm run build -- --mode $BUILD_MODE

# Upload source maps to Sentry if token is provided
RUN if [ -n "$SENTRY_AUTH_TOKEN" ] && [ -n "$OPIK_VERSION" ]; then \
      echo "Uploading source maps to Sentry..."; \
      npx sentry-cli --auth-token "$SENTRY_AUTH_TOKEN" \
        sourcemaps upload \
        --org "$SENTRY_ORG" \
        --project "$SENTRY_PROJECT" \
        --release "$OPIK_VERSION" \
        /opt/frontend/dist; \
    else \
      echo "Source maps for Sentry will not be uploaded. Missing required environment variables."; \
    fi

# Production stage
FROM nginx:1.27.4-alpine3.21

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

RUN sed -i '/access_log.*main/d' /etc/nginx/nginx.conf

EXPOSE 80

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
