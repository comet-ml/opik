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

## Stop opik

```bash
docker compose down
```
