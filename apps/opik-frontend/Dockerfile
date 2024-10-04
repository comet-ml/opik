# Build stage
FROM node:20.15.0-alpine3.20 as builder

WORKDIR /opt/frontend

COPY package*.json ./
RUN npm install

# Copy and build the application
COPY . .

ARG OPIK_VERSION
ENV VITE_APP_VERSION=${OPIK_VERSION}

ARG BUILD_MODE=production
RUN npm run build -- --mode $BUILD_MODE

# Production stage
FROM nginx:alpine

# Copy the built files from the builder stage
COPY --from=builder /opt/frontend/dist /usr/share/nginx/html

RUN sed -i '/access_log.*main/d' /etc/nginx/nginx.conf

EXPOSE 5173

# Start Nginx
CMD ["nginx", "-g", "daemon off;"]
