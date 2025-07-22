# OPIK Docker Compose Configuration

This directory contains the Docker Compose configuration for OPIK, organized using Docker profiles to support different deployment scenarios.

## Docker Profiles

The configuration uses Docker profiles to organize services into logical groups:

### Core Profiles

- **`infrastructure`**: Basic infrastructure services (MySQL, Redis, ClickHouse, ZooKeeper, MinIO)
- **`local-dev`**: Infrastructure services for local development (same as infrastructure but optimized for local use)
- **`full`**: Complete application stack including backend, frontend, and infrastructure
- **`full-with-guardrails`**: Complete stack with guardrails services enabled

### Specialized Profiles

- **`backend-only`**: Only backend services (backend + python-backend)
- **`frontend-only`**: Only frontend service
- **`guardrails`**: Guardrails backend service
- **`demo`**: Demo data generator service
- **`local-dev-migrate`**: One-time migration service for local development

## Usage Examples

### Start Full Application Stack
```bash
docker compose --profile full up -d
```

### Start Infrastructure Only (for local development)
```bash
docker compose --profile local-dev up -d
```

### Start with Guardrails
```bash
docker compose --profile full-with-guardrails up -d
```

### Run Database Migrations for Local Development
```bash
docker compose --profile local-dev-migrate run --rm local-migration
```

### With Port Mapping (using override file)
```bash
docker compose -f docker-compose.yaml -f docker-compose.override.yaml --profile full up -d
```

## Local Development Environment

For local development, use the `--local` flag with the `opik.sh` or `opik.ps1` scripts:

```bash
# Start local development environment
./opik.sh --local

# Start with database migrations
./opik.sh --local --migrate

# With port mapping enabled
./opik.sh --local --port-mapping
```

### Local Development Configuration

The local development setup uses:

1. **Docker Profile**: `local-dev` - starts only infrastructure services
2. **Environment File**: `.env.local` - centralizes environment variables
3. **Frontend Config**: `apps/opik-frontend/.env.local` - frontend environment
4. **Nginx Config**: Pre-configured to use `host.docker.internal` for local backend

## Environment Configuration

### Centralized Environment Variables

Local development environment variables are centralized in `.env.local`:

- Database connections (MySQL, ClickHouse)
- Redis configuration
- Java options
- MinIO credentials

### Frontend Environment

Frontend local development uses `apps/opik-frontend/.env.local`:
```
VITE_BASE_URL=/
VITE_BASE_API_URL=http://localhost:8080
```

## Migration from Shell Script Management

Previously, shell scripts manually managed specific services and duplicated environment variables. The new approach:

1. **Eliminates duplication**: Environment variables are centralized in `.env.local`
2. **Uses Docker profiles**: No manual service selection in scripts
3. **Simplifies maintenance**: Single source of truth for service configuration
4. **Reduces complexity**: Docker Compose handles service lifecycle

## Files Structure

```
deployment/docker-compose/
├── docker-compose.yaml           # Main compose file with profiles
├── docker-compose.override.yaml  # Port mapping overrides
├── .env.local                    # Local development environment variables
├── nginx_default_local.conf      # Nginx config for local development
├── nginx_guardrails_local.conf   # Nginx config for guardrails
└── README.md                     # This documentation
```

## Service Dependencies

Services are organized with proper dependencies:

- **Infrastructure services**: Independent, start first
- **Backend services**: Depend on infrastructure
- **Frontend**: Depends on backend
- **Demo data**: Depends on frontend and python-backend
- **Migrations**: Depend on database services

## Health Checks

All services include health checks to ensure proper startup order and system reliability:

- Database services: Connection and readiness checks
- Application services: HTTP endpoint checks
- Infrastructure services: Service-specific health endpoints

## Troubleshooting

### Check Service Status
```bash
docker compose ps
```

### View Service Logs
```bash
docker compose logs <service-name>
```

### Reset Local Environment
```bash
docker compose --profile local-dev down -v
docker compose --profile local-dev up -d
```
