# Run opik with docker-compose

## Installation Prerequisites for local installation

- Docker - https://docs.docker.com/engine/install/
- Docker Compose - https://docs.docker.com/compose/install/

## Run docker-compose using the images

If you want to use a specific version, set opik version like
```bash
export OPIK_VERSION=0.1.10
```

 otherwise it will use the latest images

 Run docker-compose
 From the root of the project

```bash
cd deployment/docker-compose
docker compose up -d
```

## Run docker-compose with building application from latest code

From the root of the project

```bash
cd deployment/docker-compose
docker compose up -d --build
```

## Exposing Database and Backend Ports for Local Development

If you're a developer and need to expose the database and backend ports to your host machine for local testing or debugging, you can use the provided Docker Compose override file.

### Steps to Expose Ports

Run the following command to start the services and expose the ports:

```bash
docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
```

This will expose the following services to the host machine:
- Redis: Available on port 6379.
- ClickHouse: Available on ports 8123 (HTTP) and 9000 (Native Protocol).
- MySQL: Available on port 3306.
- Backend: Available on ports 8080 (HTTP) and 3003 (OpenAPI specification).
- Frontend: Available on port 5173.

## Run Opik backend locally and the rest of the components with docker-compose
1. In `nginx_default_local.conf` replace
```bash
http://backend:8080
```
with your localhost

For Mac/Windows (Docker Desktop)
```bash
http://host.docker.internal:8080
```
For Linux
```bash
http://172.17.0.1:8080
```

2. Run docker-compose including exposing ports to localhost
```bash
docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
```
Stop backend container, cause you don't need it..


## Stop opik

```bash
docker compose down
```
