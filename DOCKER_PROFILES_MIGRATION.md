# Docker Profiles Migration Summary

## Overview

This document summarizes the changes made to address the feedback in [PR #2767](https://github.com/comet-ml/opik/pull/2767) regarding overlap in logic between docker-compose files and shell scripts.

## Issues Addressed

### 1. Overlap Between Docker Compose and Shell Scripts

**Before**: Shell scripts manually managed specific services using hardcoded service lists:
- `LOCAL_DEVELOPMENT_SERVICES="mysql redis clickhouse zookeeper minio mc"` (Unix)
- `$LOCAL_DEVELOPMENT_SERVICES = @("mysql", "redis", "clickhouse", "zookeeper", "minio", "mc")` (Windows)

**After**: Docker Compose profiles handle service groups:
```yaml
services:
  mysql:
    profiles: [full, local-dev, infrastructure]
  redis:
    profiles: [full, local-dev, infrastructure]
  # ... etc
```

### 2. Environment Variable Duplication

**Before**: Environment variables duplicated in both shell scripts:
```bash
# In opik.sh
export STATE_DB_PROTOCOL="jdbc:mysql://"
export STATE_DB_URL="localhost:3306/opik?..."
# ... 15+ more variables

# In opik.ps1
$env:STATE_DB_PROTOCOL = "jdbc:mysql://"
$env:STATE_DB_URL = "localhost:3306/opik?..."
# ... same 15+ variables duplicated
```

**After**: Centralized in `.env.local`:
```bash
# deployment/docker-compose/.env.local
STATE_DB_PROTOCOL=jdbc:mysql://
STATE_DB_URL=localhost:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true
# ... all variables in one place
```

### 3. Cross-Platform Duplication

**Before**: Same logic implemented twice in different languages:
- `opik.sh` - Bash/Unix implementation
- `opik.ps1` - PowerShell/Windows implementation

**After**: Common Docker Compose configuration used by both platforms.

## Changes Made

### 1. Docker Compose Configuration

#### Added Docker Profiles (`deployment/docker-compose/docker-compose.yaml`)

```yaml
# Core profiles
- infrastructure: Basic services (MySQL, Redis, ClickHouse, etc.)
- local-dev: Infrastructure for local development
- full: Complete application stack
- full-with-guardrails: Full stack with guardrails

# Specialized profiles  
- backend-only: Only backend services
- frontend-only: Only frontend service
- guardrails: Guardrails backend
- demo: Demo data generator
- local-dev-migrate: Migration service
```

#### Added Migration Service

```yaml
local-migration:
  image: ghcr.io/comet-ml/opik/opik-backend:${OPIK_VERSION:-latest}
  profiles: [local-dev-migrate]
  command: ["bash", "-c", "./run_db_migrations.sh"]
  depends_on:
    mysql: {condition: service_healthy}
    clickhouse: {condition: service_healthy}
```

### 2. Centralized Environment Configuration

#### Created `.env.local` (`deployment/docker-compose/.env.local`)
Centralized all local development environment variables that were previously duplicated.

#### Created Frontend Environment (`apps/opik-frontend/.env.local`)
```env
VITE_BASE_URL=/
VITE_BASE_API_URL=http://localhost:8080
```

#### Updated Nginx Configuration (`deployment/docker-compose/nginx_default_local.conf`)
Pre-configured to use `host.docker.internal:8080` for local development.

### 3. Refactored Shell Scripts

#### Unix Shell Script (`opik.sh`)

**Removed**:
- `LOCAL_DEVELOPMENT_SERVICES` variable
- Manual service list management
- Hardcoded environment variable exports
- Nginx configuration modification logic
- Frontend environment file creation logic

**Added**:
- `start_local_containers()` - Uses `--profile local-dev`
- `run_local_migrations()` - Uses `--profile local-dev-migrate`
- Environment loading from `.env.local`

#### PowerShell Script (`opik.ps1`)

**Removed**:
- `$LOCAL_DEVELOPMENT_SERVICES` array
- Manual service management
- Hardcoded environment variable assignments
- `Set-NginxConfigLocal` function
- `Set-FrontendConfigLocal` function

**Added**:
- `Start-LocalContainers` - Uses Docker profiles
- `Start-LocalMigrations` - Uses migration profile
- Environment loading from `.env.local`

### 4. Documentation

#### Updated README (`deployment/docker-compose/README.md`)
Comprehensive documentation of:
- Docker profiles and their usage
- Local development setup
- Environment configuration
- Migration guide from old approach

#### Created Test Script (`test-profiles.sh`)
Demonstrates the new approach and its benefits.

## Benefits of the New Approach

### 1. Reduced Duplication
- Environment variables: Single source in `.env.local`
- Service definitions: Managed by Docker Compose profiles
- Platform logic: Common Docker configuration

### 2. Improved Maintainability
- Single source of truth for service configuration
- No need to sync changes across multiple scripts
- Easier to add new profiles or services

### 3. Better Separation of Concerns
- Docker Compose: Service lifecycle and configuration
- Shell scripts: Local build and development workflow
- Environment files: Configuration values

### 4. Enhanced Flexibility
- Easy to create new deployment scenarios with profiles
- Simple to add or remove services from groups
- No hardcoded service lists in scripts

## Usage Examples

### Before (Manual Service Management)
```bash
# Hard-coded in script
LOCAL_DEVELOPMENT_SERVICES="mysql redis clickhouse zookeeper minio mc"
docker compose up -d $LOCAL_DEVELOPMENT_SERVICES

# Duplicated environment setup
export STATE_DB_PROTOCOL="jdbc:mysql://"
export STATE_DB_URL="localhost:3306/opik?..."
# ... many more exports
```

### After (Profile-Based)
```bash
# Simple profile usage
docker compose --profile local-dev up -d

# Centralized environment loading
source deployment/docker-compose/.env.local
```

### Local Development Commands
```bash
# Start local development (infrastructure + local backend/frontend)
./opik.sh --local

# Start with migrations
./opik.sh --local --migrate

# Same commands work on Windows with opik.ps1
```

## Backward Compatibility

The changes maintain backward compatibility:
- Existing `./opik.sh` and `./opik.ps1` commands work unchanged
- Default behavior (no flags) remains the same
- All original flags (`--debug`, `--port-mapping`, `--guardrails`) still work

## Testing

The new approach can be tested using:
```bash
# Run the test script
./test-profiles.sh

# Manual testing
docker compose --profile local-dev up -d
docker compose --profile local-dev-migrate run --rm local-migration
```

## Conclusion

This refactoring successfully addresses all the feedback points:

1. ✅ **Eliminated overlap** between docker-compose and shell scripts
2. ✅ **Removed environment variable duplication** through centralization
3. ✅ **Reduced cross-platform duplication** by using common Docker configuration
4. ✅ **Improved maintainability** with single source of truth
5. ✅ **Enhanced flexibility** through Docker profiles strategy

The new approach is cleaner, more maintainable, and follows Docker Compose best practices while preserving all existing functionality.