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
docker compose -f docker-compose.yaml up -d
```

## Run docker-compose with building application from latest code

From the root of the project:

```bash
cd deployment/docker-compose
docker compose -f docker-compose.yaml up -d --build
```

## Exposing Database and Backend Ports for Local Development

If you're a developer and need to expose the database and backend ports to your host machine for local testing or
debugging, you can use the provided Docker Compose override file.

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
- Python backend: Available on port 8000 (HTTP).
- Frontend: Available on port 5173.

## Binding Ports in Docker Compose

By default, Docker Compose binds exposed container ports to 0.0.0.0, making them accessible from any network interface
on the host. To restrict access, specify a specific IP in the ports section, such as 127.0.0.1:8080:80, to limit
exposure to the local machine.
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
docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
```

Stop the backend container, because you don't need it.

## Running the Front-End Locally for Development

If you want to run the front-end locally and see your changes instantly upon saving files, follow this guide:

### Prerequisites

1. Ensure you have **Node.js** installed.

### Steps

#### 1. Configure the Environment Variables

- Navigate to `apps/opik-frontend/.env.development` and update it with the following values:

  ```ini
  VITE_BASE_URL=/
  VITE_BASE_API_URL=http://localhost:8080
  ```

#### 2. Enable CORS in the Back-End

- Open `deployment/docker-compose/docker-compose.yaml` and in the `services.backend.environment` section,
  add `CORS: true` to allow cross-origin requests.

  It should look like this:

  ```yaml
  ...
  OPIK_USAGE_REPORT_ENABLED: ${OPIK_USAGE_REPORT_ENABLED:-true}
  CORS: true
  ...
  ```

#### 3. Start the Services

- Run the following command to start the necessary services and expose the required ports:

  ```bash
  docker compose -f docker-compose.yaml -f docker-compose.override.yaml up -d
  ```

#### 4. Verify the Back-End is Running

- Wait for the images to build and containers to start.
- To confirm that the back-end is running, open the following URL in your browser:

  ```
  http://localhost:8080/is-alive/ver
  ```

    - If you see a version number displayed, the back-end is running successfully.

#### 5. Install Front-End Dependencies

- Navigate to the front-end project directory:

  ```bash
  cd opik/apps/opik-frontend
  ```

- Install the necessary dependencies:

  ```bash
  npm install
  ```

#### 6. Start the Front-End

- Run the following command to start the front-end:

  ```bash
  npm run start
  ```

- Once the script completes, open your browser and go to:

  ```
  http://localhost:5174/
  ```

  You should see the app running! 🎉

### Notes:

- Another built front-end version will be available at `http://localhost:5173/`.  
  This version is used for checking builds, but you can also use it for the same purposes if needed.

## Stop Opik

```bash
docker compose down
```
