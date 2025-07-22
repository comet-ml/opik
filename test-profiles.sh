#!/bin/bash

# Test script to demonstrate the new Docker profiles approach
# This shows how the new system replaces manual service management

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "🧪 Testing OPIK Docker Profiles Approach"
echo "=========================================="

# Test 1: Start infrastructure only (equivalent to old LOCAL_DEVELOPMENT_SERVICES)
echo ""
echo "📦 Test 1: Starting infrastructure services only (local-dev profile)"
echo "This replaces manual service selection: mysql redis clickhouse zookeeper minio mc"
docker compose -f deployment/docker-compose/docker-compose.yaml --profile local-dev up -d

echo ""
echo "⏳ Waiting for services to be healthy..."
sleep 10

echo ""
echo "✅ Infrastructure services status:"
docker compose -f deployment/docker-compose/docker-compose.yaml ps

# Test 2: Run migrations using profile
echo ""
echo "📦 Test 2: Running database migrations using profile"
echo "This replaces manual migration scripts in shell scripts"
docker compose -f deployment/docker-compose/docker-compose.yaml --profile local-dev-migrate run --rm local-migration

# Test 3: Demonstrate environment variable centralization
echo ""
echo "📦 Test 3: Environment variable centralization"
echo "Environment variables are now centralized in .env.local instead of duplicated in shell scripts:"
if [ -f "deployment/docker-compose/.env.local" ]; then
    echo ""
    echo "🔧 Local development environment variables:"
    grep -E "^[A-Z_]+" deployment/docker-compose/.env.local | head -10
    echo "... (and more)"
else
    echo "❌ .env.local not found"
fi

# Test 4: Show profile flexibility
echo ""
echo "📦 Test 4: Profile flexibility demonstration"
echo "Different profiles for different use cases:"

echo ""
echo "🏗️  Available profiles:"
echo "  - local-dev: Infrastructure only for local development"
echo "  - full: Complete application stack"
echo "  - guardrails: Add guardrails services"
echo "  - backend-only: Only backend services"
echo "  - frontend-only: Only frontend service"

# Test 5: Cleanup and show how easy it is
echo ""
echo "📦 Test 5: Easy cleanup with profiles"
docker compose -f deployment/docker-compose/docker-compose.yaml --profile local-dev down

echo ""
echo "✅ Tests completed!"
echo ""
echo "🎯 Benefits of the new approach:"
echo "  ✅ No environment variable duplication between shell scripts"
echo "  ✅ No manual service management in scripts"
echo "  ✅ Centralized configuration in docker-compose"
echo "  ✅ Single source of truth for service definitions"
echo "  ✅ Easy to extend with new profiles"
echo "  ✅ No platform-specific differences (Windows/Unix)"
echo ""
echo "🔄 Migration summary:"
echo "  Before: Shell scripts manually managed services and duplicated env vars"
echo "  After:  Docker profiles handle service groups, centralized env configuration"