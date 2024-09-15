# Run opik with docker-compose

## Installation Prerequisites for local installation

- Docker - https://docs.docker.com/engine/install/
- Docker Compose - https://docs.docker.com/compose/install/

## Run docker-compose using the images

From the root of the project
Set opik version or it will use the latest images

```bash
cd deployment/docker-compose
OPIK_VERSION=0.1.10
docker compose up -d
```

## Run docker-compose with building application from latest code

From the root of the project

```bash
cd deployment/docker-compose
docker compose -f docker-compose-build.yaml up -d --build
```
