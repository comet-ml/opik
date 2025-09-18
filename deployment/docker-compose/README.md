# Run Opik with `docker compose`

## Installation pre-requirements for local installation

- Docker: https://docs.docker.com/engine/install/
- Docker Compose: https://docs.docker.com/compose/install/

## Service Profiles for Development

Opik supports Docker Compose profiles to start different combinations of services for various development scenarios:

### Available Profiles

1. **Infrastructure Services** (always enabled): MySQL, Redis, ClickHouse, ZooKeeper, MinIO etc.
2. **Backend Services** (`backend`): Infrastructure (automatic) + Backend, Python Backend services etc. 
3. **Opik Services** (`opik`): The full Opik suite including all infrastructure and services, except for Guardrails services
4. **Guardrails** (`guardrails`): Guardrails services, meant to be combined with other profiles. Guardrails are always optional by default, even for the full Opik suite, unless explicitly enabled

### Profile Usage Examples

**Start only infrastructure services (default behavior when no profile):**
```bash
docker compose up -d
```

**Start infrastructure + backend services:**
```bash
docker compose --profile backend up -d
```

**Start full Opik suite (all infrastructure and services, except guardrails):**
```bash
docker compose --profile opik up -d
```

**Start backend + guardrails:**
```bash
docker compose --profile backend --profile guardrails up -d
```

**Start full Opik suite + guardrails:**
```bash
docker compose --profile opik --profile guardrails up -d
```

**Note**: Infrastructure services (databases, caches, storage etc.) always start by default, as that's the expected behaviour for services with no profile, see [Using profiles with Compose](https://docs.docker.com/compose/how-tos/profiles/). Any profile such as Backend, full Opik suite etc. always automatically include the infrastructure.

## Run `docker compose` using the images

If you want to use a specific version, set Opik version like:

```bash
export OPIK_VERSION=0.1.10
```

Otherwise, it will use the latest images.

Run `docker compose` from the root of the project:

```bash
cd deployment/docker-compose

# Optionally, you can force a pull of the latest images
docker compose --profile opik pull

docker compose -f docker-compose.yaml --profile opik up -d
```

## Run `docker compose` with building application from latest code

From the root of the project:
```bash
cd deployment/docker-compose

# Optionally, you can force a pull of the latest images
docker compose --profile opik pull

# Build the images
docker compose -f docker-compose.yaml --profile opik up -d --build

# Alternatively, you can force a pull of the latest images and build the images
docker compose -f docker-compose.yaml --profile opik up -d --build --pull always
```

## Exposing Database and Backend Ports for Local Development

If you're a developer and need to expose the database and backend ports to your host machine for local testing or
debugging, you can use the provided Docker Compose override file.

### Steps to Expose Ports

Run the following command to start the services and expose the ports:

```bash
# Optionally, you can force a pull of the latest images
docker compose --profile opik pull

docker compose -f docker-compose.yaml -f docker-compose.override.yaml --profile opik up -d
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

## Run Opik backend locally and the rest of the components with `docker compose`

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

2. Run `docker compose` including exposing ports to localhost:

```bash
# Optionally, you can force a pull of the latest images
docker compose --profile opik pull

docker compose -f docker-compose.yaml -f docker-compose.override.yaml --profile opik up -d
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
docker compose --profile opik up -d
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
docker compose --profile opik up -d
```

When disabled, NGINX logs normally to files and Fluent Bit does not run.

## Stop Opik

```bash
docker compose --profile opik down
```
