# Run opik with docker-compose

## Installation Prerequisites for local installation

- Docker - https://docs.docker.com/engine/install/
- Docker Compose - https://docs.docker.com/compose/install/

## Run docker-compose using the latest images

From the root of the project

```bash
cd deployment/docker-compose
docker compose up -d
```

## Run docker-compose with building application from latest code

From the root of the project

```bash
cd deployment/docker-compose
docker compose -f docker-compose-build.yaml up -d --build
```
