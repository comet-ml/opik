# Local Development Script

This directory contains a script for running OPIK locally for development purposes.

## `dev-local.sh`

A comprehensive shell script that sets up the OPIK development environment by:
1. Starting all required containers (excluding backend & frontend)
2. Building the backend with Maven (skipping tests)
3. Running the backend and frontend locally

### Prerequisites

Before running the script, ensure you have the following installed:

- **Docker** - For running containers
- **Maven** - For building the Java backend
- **Node.js** - For running the frontend
- **npm** - For managing frontend dependencies

### Usage

#### Basic Usage (Full Setup)
```bash
./scripts/dev-local.sh
```

This will:
- Start all required containers (MySQL, Redis, ClickHouse, Zookeeper, MinIO, Python Backend)
- Build the backend with Maven (skipping tests)
- Run the backend locally on http://localhost:8080
- Run the frontend locally on http://localhost:5173

#### Options

```bash
# Only start containers (don't run backend/frontend locally)
./scripts/dev-local.sh --containers-only

# Only build and run backend locally
./scripts/dev-local.sh --backend-only

# Only run frontend locally
./scripts/dev-local.sh --frontend-only

# Show help
./scripts/dev-local.sh --help
```

### What the Script Does

#### Container Management
- Starts the following containers using Docker Compose:
  - `mysql` - Database for state management
  - `redis` - Caching and session storage
  - `clickhouse` - Analytics database
  - `zookeeper` - Distributed coordination
  - `minio` - Object storage (S3-compatible)
  - `python-backend` - Python evaluation service

- Configures nginx for local development (updates `nginx_default_local.conf`)
- Waits for all containers to be healthy before proceeding
- Uses the same container configuration as the main `opik.sh` script

#### Backend Setup
- Changes to the `apps/opik-backend` directory
- Runs `mvn clean install -DskipTests` to build the backend
- Sets up all necessary environment variables for local development
- Runs database migrations using `./run_db_migrations.sh`
- Starts the backend server using the built JAR file
- Waits for the backend to be accessible on http://localhost:8080

#### Frontend Setup
- Changes to the `apps/opik-frontend` directory
- Installs npm dependencies if `node_modules` doesn't exist
- Configures `.env.development` with local development settings:
  - `VITE_BASE_URL=/`
  - `VITE_BASE_API_URL=http://localhost:8080`
- Starts the Vite development server using `npm start`
- Waits for the frontend to be accessible on http://localhost:5173

### Configuration Files

The script automatically configures the following files for local development:

#### Frontend Environment (`.env.development`)
```bash
VITE_BASE_URL=/
VITE_BASE_API_URL=http://localhost:8080
```

#### Nginx Configuration (`nginx_default_local.conf`)
Updates the proxy_pass directive to use `host.docker.internal:8080` instead of `backend:8080` for local development.

### Environment Variables

The script sets up the following environment variables for the backend:

```bash
STATE_DB_PROTOCOL=jdbc:mysql://
STATE_DB_URL=localhost:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true
STATE_DB_DATABASE_NAME=opik
STATE_DB_USER=opik
STATE_DB_PASS=opik
ANALYTICS_DB_MIGRATIONS_URL=jdbc:clickhouse://localhost:8123
ANALYTICS_DB_MIGRATIONS_USER=opik
ANALYTICS_DB_MIGRATIONS_PASS=opik
ANALYTICS_DB_PROTOCOL=HTTP
ANALYTICS_DB_HOST=localhost
ANALYTICS_DB_PORT=8123
ANALYTICS_DB_DATABASE_NAME=opik
ANALYTICS_DB_USERNAME=opik
ANALYTICS_DB_PASS=opik
JAVA_OPTS=-Dliquibase.propertySubstitutionEnabled=true -XX:+UseG1GC -XX:MaxRAMPercentage=80.0
REDIS_URL=redis://:opik@localhost:6379/
OPIK_OTEL_SDK_ENABLED=false
OTEL_VERSION=2.16.0
OTEL_PROPAGATORS=tracecontext,baggage,b3
OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED=true
OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION=BASE2_EXPONENTIAL_BUCKET_HISTOGRAM
OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS=process.command_args
OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE=delta
OPIK_USAGE_REPORT_ENABLED=true
AWS_ACCESS_KEY_ID=THAAIOSFODNN7EXAMPLE
AWS_SECRET_ACCESS_KEY=LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
PYTHON_EVALUATOR_URL=http://localhost:8000
TOGGLE_GUARDRAILS_ENABLED=false
```

### Stopping the Services

- Press `Ctrl+C` to stop all services gracefully
- The script will automatically clean up background processes
- Containers will continue running (use `./opik.sh --stop` to stop them)

### Troubleshooting

#### Common Issues

1. **Docker not running**
   ```
   [ERROR] Docker is not running or not accessible. Please start Docker first.
   ```
   - Start Docker Desktop or Docker daemon

2. **Maven not found**
   ```
   [ERROR] Maven is not installed. Please install Maven first.
   ```
   - Install Maven: `sudo apt install maven` (Ubuntu/Debian) or `brew install maven` (macOS)

3. **Node.js not found**
   ```
   [ERROR] Node.js is not installed. Please install Node.js first.
   ```
   - Install Node.js from https://nodejs.org/

4. **Backend build fails**
   - Check that you have Java 17+ installed
   - Ensure you have sufficient memory for Maven build
   - Check the Maven output for specific error messages

5. **Containers not starting**
   - Check Docker logs: `docker logs opik-mysql-1` (replace with container name)
   - Ensure ports are not already in use
   - Check available disk space

#### Debugging

- The script provides colored output to help identify issues
- Check container health: `docker ps` to see running containers
- Check container logs: `docker logs <container-name>`
- Verify ports are accessible: `curl http://localhost:8080/health-check`

### Integration with IDE

You can use this script to start the infrastructure, then run the backend and frontend directly from your IDE:

1. Run `./scripts/dev-local.sh --containers-only` to start containers
2. Run the backend from your IDE (e.g., IntelliJ IDEA, VS Code)
3. Run the frontend from your IDE or terminal

This approach gives you better debugging capabilities and faster development cycles.