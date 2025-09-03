# Run Opik with docker-compose

## Installation pre-requirements for local installation

- Docker: https://docs.docker.com/engine/install/
- Docker Compose: https://docs.docker.com/compose/install/

## Run docker-compose using the images

If you want to use a specific version, set Opik version like:

```bash
export OPIK_VERSION=0.1.10
```

Otherwise, it will use the latest images.

Run docker-compose from the root of the project:

```bash
cd deployment/docker-compose

# Optionally, you can force a pull of the latest images
docker compose pull

docker compose -f docker-compose.yaml up -d
```

## Run docker-compose with building application from latest code

From the root of the project:
```bash
cd deployment/docker-compose

# Optionally, you can force a pull of the latest images
docker compose pull

# Build the images
docker compose -f docker-compose.yaml up -d --build

# Alternatively, you can force a pull of the latest images and build the images
docker compose -f docker-compose.yaml up -d --build --pull always
```

## Exposing Database and Backend Ports for Local Development

If you're a developer and need to expose the database and backend ports to your host machine for local testing or
debugging, you can use the provided Docker Compose override file.

### Steps to Expose Ports

Run the following command to start the services and expose the ports:

```bash
# Optionally, you can force a pull of the latest images
docker compose pull

docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
```

This will expose the following services to the host machine:

- Redis: Available on port 6379.
- ClickHouse: Available on ports 8123 (HTTP) and 9000 (Native Protocol).
- Zookeeper:  Available on port 2181.
- MySQL: Available on port 3306.
- Backend: Available on ports 8080 (HTTP) and 3003 (OpenAPI specification).
- Python backend: Available on port 8000 (HTTP).
- Frontend: Available on port 5173.

## Binding Ports in Docker Compose
By default, Docker Compose binds exposed container ports to 0.0.0.0, making them accessible from any network interface on the host. To restrict access, specify a specific IP in the ports section, such as 127.0.0.1:8080:80, to limit exposure to the local machine.
This can be done in `docker-compose.yaml` file
```
frontend:
    ports:
      - "127.0.0.1:5173:5173" # Frontend server port

```

## Run Opik backend locally and the rest of the components with docker-compose

1. In `nginx_default_local.conf` replace:

```bash
http://backend:8080
```

With your localhost.

For Mac/Windows (Docker Desktop):

```bash
http://host.docker.internal:8080
```

For Linux:

```bash
http://172.17.0.1:8080
```

2. Run docker-compose including exposing ports to localhost:

```bash
# Optionally, you can force a pull of the latest images
docker compose pull

docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
```

Stop the backend container, because you don't need it.

## Log Shipping to OpenTelemetry

Opik frontend can ship NGINX access logs and Fluent Bit metrics to an OpenTelemetry Collector.

### Configuration

Set the following environment variables to enable log shipping:

```bash
# Enable log shipping
export ENABLE_OTEL_LOG_EXPORT=true

# Configure collector endpoint
export OTEL_COLLECTOR_HOST=your-otel-collector-host
export OTEL_COLLECTOR_PORT=4317

# Run with log shipping enabled
docker compose up -d
```

### What Gets Shipped

When enabled, the frontend will export:

- **NGINX Access Logs**: All HTTP requests to the frontend
- **Fluent Bit Metrics**: Internal metrics about log processing performance

### Monitoring Endpoints

When log shipping is enabled, additional monitoring endpoints are available:

- **Frontend**: `http://localhost:5173/` (main application)
- **Fluent Bit**: `http://localhost:2020/` (metrics and health checks)

### Configuration Files

- `fluent-bit.conf`: Fluent Bit configuration for log tailing and metrics collection
- `nginx_default_local.conf`: NGINX configuration with standard access logging

### Disabling Log Shipping

To disable log shipping (default):

```bash
export ENABLE_OTEL_LOG_EXPORT=false
# or simply omit the variable
docker compose up -d
```

When disabled, NGINX logs normally to files and Fluent Bit does not run.

## Stop Opik

```bash
docker compose down
```
