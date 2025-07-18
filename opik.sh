#!/bin/bash
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REQUIRED_CONTAINERS=("opik-clickhouse-1" "opik-mysql-1" "opik-python-backend-1" "opik-redis-1" "opik-frontend-1" "opik-backend-1" "opik-minio-1" "opik-zookeeper-1")
GUARDRAILS_CONTAINERS=("opik-guardrails-backend-1")

# Local development uses specific services defined in LOCAL_DEVELOPMENT_SERVICES (excludes backend/frontend containers)
# Note: demo-data-generator is excluded because it depends on frontend/python-backend containers
LOCAL_DEVELOPMENT_SERVICES="mysql redis clickhouse zookeeper minio mc"

# Bash doesn't have straight forward support for returning arrays, so using a global var instead
CONTAINERS=("${REQUIRED_CONTAINERS[@]}")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

get_verify_cmd() {
  local cmd="./opik.sh"
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    cmd="$cmd --guardrails"
  fi
  echo "$cmd --verify"
}

get_start_cmd() {
  local cmd="./opik.sh"
  if [[ "$BUILD_MODE" == "true" ]]; then
    cmd="$cmd --build"
  fi
  if [[ "$DEBUG_MODE" == "true" ]]; then
    cmd="$cmd --debug"
  fi
  if [[ "$PORT_MAPPING" == "true" ]]; then
    cmd="$cmd --port-mapping"
  fi
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    cmd="$cmd --guardrails"
  fi
  echo "$cmd"
}

generate_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen
  else
    cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s%N
  fi
}

get_docker_compose_cmd() {
  local cmd="docker compose -f $script_dir/deployment/docker-compose/docker-compose.yaml"
  if [[ "$PORT_MAPPING" == "true" ]]; then
    cmd="$cmd -f $script_dir/deployment/docker-compose/docker-compose.override.yaml"
  fi
  echo "$cmd"
}

print_usage() {
  echo "Usage: opik.sh [OPTIONS]"
  echo ""
  echo "Options:"
  echo "  --verify        Check if all containers are healthy"
  echo "  --info          Display welcome system status, only if all containers are running"
  echo "  --stop          Stop all containers and clean up"
  echo "  --build         Build containers before starting (can be combined with other flags)"
  echo "  --debug         Enable debug mode (verbose output) (can be combined with other flags)"
  echo "  --port-mapping  Enable port mapping for all containers by using the override file (can be combined with other flags)"
  echo "  --guardrails    Enable guardrails profile (can be combined with other flags)"
  echo "  --local         Start local development environment (containers + local backend/frontend)"
  echo "  --migrate       Run database migrations (use with --local)"
  echo "  --help          Show this help message"
  echo ""
  echo "If no option is passed, the script will start missing containers and then show the system status."
}

# Local development functions

# Function to check if required tools are installed
check_local_requirements() {
    print_status "Checking local development requirements..."

    if ! command_exists mvn; then
        print_error "Maven is not installed. Please install Maven first."
        exit 1
    fi

    if ! command_exists node; then
        print_error "Node.js is not installed. Please install Node.js first."
        exit 1
    fi

    if ! command_exists npm; then
        print_error "npm is not installed. Please install npm first."
        exit 1
    fi

    print_success "All local development requirements are met"
}

# Function to configure nginx for local development
configure_nginx_local() {
    print_status "Configuring nginx for local development..."

    nginx_config="$script_dir/deployment/docker-compose/nginx_default_local.conf"

    # Update nginx configuration to use host.docker.internal for backend
    if grep -q "proxy_pass http://backend:8080;" "$nginx_config"; then
        # Handle both macOS and Linux sed syntax
        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' 's|proxy_pass http://backend:8080;|proxy_pass http://host.docker.internal:8080;|' "$nginx_config"
        else
            sed -i 's|proxy_pass http://backend:8080;|proxy_pass http://host.docker.internal:8080;|' "$nginx_config"
        fi
        print_success "Nginx configuration updated for local development"
    elif grep -q "proxy_pass http://host.docker.internal:8080;" "$nginx_config"; then
        print_status "Nginx configuration already set for local development"
    else
        print_warning "Could not find expected proxy_pass configuration in nginx file"
    fi
}

# Function to configure frontend for local development
configure_frontend_local() {
    print_status "Configuring frontend for local development..."

    frontend_dir="$script_dir/apps/opik-frontend"

    # Ensure .env.development has the correct configuration for local development
    if [ ! -f "$frontend_dir/.env.development" ]; then
        print_warning ".env.development file not found, creating it..."
    fi

    # Update .env.development with correct local development settings
    cat > "$frontend_dir/.env.development" << EOF
VITE_BASE_URL=/
VITE_BASE_API_URL=http://localhost:8080
EOF

    print_success "Frontend environment configured for local development"
}

# Function to start containers for local development
start_local_containers() {
    print_status "Starting required containers for local development (supporting services only, excluding backend/frontend/python-backend containers)..."

    # Change to project root
    cd "$script_dir"

    # Configure nginx for local development
    configure_nginx_local

    # Configure frontend environment for local development
    configure_frontend_local

    # Start containers using docker compose with port mapping override 
    # Force port mapping to true for local development
    docker compose -f deployment/docker-compose/docker-compose.yaml -f deployment/docker-compose/docker-compose.override.yaml up -d "$LOCAL_DEVELOPMENT_SERVICES"

    print_status "Waiting for containers to be healthy..."

    # Wait for containers to be healthy
    local max_retries=60
    local interval=2

    # Check container health for supporting services only
    local container_health_checks=("opik-mysql-1" "opik-redis-1" "opik-clickhouse-1" "opik-zookeeper-1" "opik-minio-1")
    
    for container in "${container_health_checks[@]}"; do
        print_status "Waiting for $container..."
        for i in $(seq 1 $max_retries); do
            if docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null | grep -q "healthy"; then
                print_success "$container is healthy"
                break
            fi
            if [ $i -eq $max_retries ]; then
                if [ "$container" = "opik-python-backend-1" ]; then
                    print_warning "$container may not be fully healthy, but continuing..."
                else
                    print_error "$container failed to become healthy after ${max_retries}s"
                    exit 1
                fi
            fi
            sleep $interval
        done
    done

    print_success "All required containers for local development are running"
}

# Function to build backend with Maven
build_backend_local() {
    print_status "Building backend with Maven (skipping tests)..."

    backend_dir="$script_dir/apps/opik-backend"

    cd "$backend_dir"

    # Clean and install, skipping tests
    mvn clean install -DskipTests

    if [ $? -eq 0 ]; then
        print_success "Backend built successfully"
    else
        print_error "Backend build failed"
        exit 1
    fi
}

# Function to run backend locally
run_backend_local() {
    print_status "Starting backend locally..."

    backend_dir="$script_dir/apps/opik-backend"

    cd "$backend_dir"

    # Set environment variables for local development
    export CORS="true"
    export STATE_DB_PROTOCOL="jdbc:mysql://"
    export STATE_DB_URL="localhost:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true"
    export STATE_DB_DATABASE_NAME="opik"
    export STATE_DB_USER="opik"
    export STATE_DB_PASS="opik"
    export ANALYTICS_DB_MIGRATIONS_URL="jdbc:clickhouse://localhost:8123"
    export ANALYTICS_DB_MIGRATIONS_USER="opik"
    export ANALYTICS_DB_MIGRATIONS_PASS="opik"
    export ANALYTICS_DB_PROTOCOL="HTTP"
    export ANALYTICS_DB_HOST="localhost"
    export ANALYTICS_DB_PORT="8123"
    export ANALYTICS_DB_DATABASE_NAME="opik"
    export ANALYTICS_DB_USERNAME="opik"
    export ANALYTICS_DB_PASS="opik"
    export JAVA_OPTS="-Dliquibase.propertySubstitutionEnabled=true -XX:+UseG1GC -XX:MaxRAMPercentage=80.0"
    export REDIS_URL="redis://:opik@localhost:6379/"

    # Run database migrations if --migrate flag is specified
    if [ "$RUN_MIGRATIONS" = true ]; then
        print_status "Running database migrations..."
        # Set OPIK_VERSION for the migration script and create symlink
        JAR_FILE=$(find target -name "opik-backend-*.jar" ! -name "*sources*" ! -name "original-*" | head -n 1)
        if [ -n "$JAR_FILE" ]; then
            # Extract version from JAR filename (e.g., opik-backend-1.0-SNAPSHOT.jar -> 1.0-SNAPSHOT)
            OPIK_VERSION=$(basename "$JAR_FILE" | sed 's/opik-backend-\(.*\)\.jar/\1/')
            export OPIK_VERSION
            
            # Create symlink for migration script (it expects JAR in current directory)
            MIGRATION_JAR="opik-backend-$OPIK_VERSION.jar"
            if [ ! -f "$MIGRATION_JAR" ]; then
                ln -sf "$JAR_FILE" "$MIGRATION_JAR"
            fi
        fi
        ./run_db_migrations.sh
        
        # Clean up symlink after migrations
        if [ -n "$MIGRATION_JAR" ] && [ -L "$MIGRATION_JAR" ]; then
            rm "$MIGRATION_JAR"
        fi
    else
        print_status "Skipping database migrations (use --migrate flag to run them)"
    fi

    # Start the backend
    print_status "Starting backend server..."
    # Use wildcard pattern for JAR filename to avoid hardcoding version
    JAR_FILE=$(find target -name "opik-backend-*.jar" ! -name "*sources*" ! -name "original-*" | head -n 1)
    if [ -z "$JAR_FILE" ]; then
        print_error "No backend JAR file found in target directory. Please build the backend first."
        exit 1
    fi
    print_status "Using JAR file: $JAR_FILE"
    java "$JAVA_OPTS" -jar "$JAR_FILE" server config.yml &
    BACKEND_PID=$!

    # Wait for backend to start
    print_status "Waiting for backend to start..."
    for i in $(seq 1 30); do
        if curl -f http://localhost:8080/health-check >/dev/null 2>&1; then
            print_success "Backend is running on http://localhost:8080"
            break
        fi
        if [ $i -eq 30 ]; then
            print_error "Backend failed to start after 30 attempts"
            kill $BACKEND_PID 2>/dev/null || true
            exit 1
        fi
        sleep 2
    done
}

# Function to run frontend locally
run_frontend_local() {
    print_status "Starting frontend locally..."

    frontend_dir="$script_dir/apps/opik-frontend"

    cd "$frontend_dir"

    # Install dependencies if node_modules doesn't exist
    if [ ! -d "node_modules" ]; then
        print_status "Installing frontend dependencies..."
        npm install
    fi

    # Frontend environment is already configured in start_local_containers

    # Start the frontend development server
    print_status "Starting frontend development server..."
    npm start &
    FRONTEND_PID=$!

    # Wait for frontend to start
    print_status "Waiting for frontend to start..."
    for i in $(seq 1 30); do
        if curl -f http://localhost:5174 >/dev/null 2>&1; then
            print_success "Frontend is running on http://localhost:5174"
            break
        fi
        if [ $i -eq 30 ]; then
            print_error "Frontend failed to start after 30 attempts"
            kill $FRONTEND_PID 2>/dev/null || true
            exit 1
        fi
        sleep 2
    done
}

# Function to handle cleanup on script exit (for local development)
cleanup_local() {
    print_status "Cleaning up local development environment..."

    # Kill background processes
    if [ ! -z "$BACKEND_PID" ]; then
        print_status "Stopping backend..."
        kill $BACKEND_PID 2>/dev/null || true
    fi

    if [ ! -z "$FRONTEND_PID" ]; then
        print_status "Stopping frontend..."
        kill $FRONTEND_PID 2>/dev/null || true
    fi

    print_success "Local development cleanup completed"
}

# Function to run local development environment
run_local_development() {
    print_status "Starting OPIK local development environment..."

    # Set up signal handlers for cleanup
    trap cleanup_local EXIT INT TERM

    # Check requirements
    check_local_requirements
    check_docker_status

    # Start containers for local development
    start_local_containers

    # Full setup: build and run both backend and frontend
    build_backend_local
    run_backend_local
    run_frontend_local

    print_success "OPIK local development environment is ready!"
    print_status "Backend: http://localhost:8080"
    print_status "Frontend: http://localhost:5174"
    print_status "Press Ctrl+C to stop all services"

    # Wait for user to stop
    wait
}

# End of local development functions

check_docker_status() {
  # Ensure Docker is running
  if ! docker info >/dev/null 2>&1; then
    echo "❌ Docker is not running or not accessible. Please start Docker first."
    exit 1
  fi
}

check_containers_status() {
  local show_output="${1:-false}"
  local all_ok=true

  check_docker_status

  local containers=("${CONTAINERS[@]}")

  for container in "${containers[@]}"; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
    health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)

    if [[ "$status" != "running" ]]; then
      echo "❌ $container is not running (status=$status)"
      all_ok=false
    elif [[ "$health" != "" && "$health" != "healthy" ]]; then
      echo "❌ $container is running but not healthy (health=$health)"
      all_ok=false
    else
      [[ "$show_output" == "true" ]] && echo "✅ $container is running and healthy"
    fi
  done

  $all_ok && return 0 || return 1
}

start_missing_containers() {
  check_docker_status

  uuid=$(generate_uuid)
  start_time=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  send_install_report "$uuid" "false" "$start_time"

  [[ "$DEBUG_MODE" == true ]] && echo "🔍 Checking required containers..."
  all_running=true

  local containers=("${CONTAINERS[@]}")
  for container in "${containers[@]}"; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)

    if [[ "$status" != "running" ]]; then
      [[ "$DEBUG_MODE" == true ]] && echo "🔴 $container is not running (status: ${status:-not found})"
      all_running=false
    else
      [[ "$DEBUG_MODE" == true ]] && echo "✅ $container is already running"
    fi
  done

  echo "🔄 Starting missing containers..."

  if [[ "${BUILD_MODE}" = "true" ]]; then
    if docker buildx bake --help >/dev/null 2>&1; then
      echo "ℹ️ Bake is available on Docker Buildx. Exporting COMPOSE_BAKE=true"
      export COMPOSE_BAKE=true
    else
      echo "ℹ️ Bake is not available on Docker Buildx. Not using it for builds"
    fi
  fi

  local cmd
  cmd=$(get_docker_compose_cmd)
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    cmd="$cmd --profile guardrails"
  fi
  $cmd up -d ${BUILD_MODE:+--build}

  echo "⏳ Waiting for all containers to be running and healthy..."
  max_retries=60
  interval=1
  all_running=true

  for container in "${containers[@]}"; do
    retries=0
    [[ "$DEBUG_MODE" == true ]] && echo "⏳ Waiting for $container..."

    while true; do
      status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
      health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)

      if [[ "$status" != "running" ]]; then
        echo "❌ $container failed to start (status: $status)"
        break
      fi

      if [[ "$health" == "healthy" ]]; then
        [[ "$DEBUG_MODE" == true ]] && echo "✅ $container is now running and healthy!"
        break
      elif [[ "$health" == "starting" ]]; then
        [[ "$DEBUG_MODE" == true ]] && echo "⏳ $container is starting... retrying (${retries}s)"
        sleep "$interval"
        retries=$((retries + 1))
        if [[ $retries -ge $max_retries ]]; then
          echo "⚠️  $container is still not healthy after ${max_retries}s"
          all_running=false
          break
        fi
      else
        echo "❌ $container health state is '$health'"
        all_running=false
        break
      fi
    done
  done

  if $all_running; then
    send_install_report "$uuid" "true" "$start_time"
  fi
}

stop_containers() {
  check_docker_status
  echo "🛑 Stopping all required containers..."
  local cmd
  cmd=$(get_docker_compose_cmd)
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    cmd="$cmd --profile guardrails"
  fi
  $cmd down
  echo "✅ All containers stopped and cleaned up!"
}

print_banner() {
  check_docker_status
  frontend_port=$(docker inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>/dev/null)
  ui_url="http://localhost:${frontend_port:-5173}"

  echo ""
  echo "╔═════════════════════════════════════════════════════════════════╗"
  echo "║                                                                 ║"
  echo "║                       🚀 OPIK PLATFORM 🚀                       ║"
  echo "║                                                                 ║"
  echo "╠═════════════════════════════════════════════════════════════════╣"
  echo "║                                                                 ║"
  echo "║  ✅ All services started successfully!                          ║"
  echo "║                                                                 ║"
  echo "║  📊 Access the UI:                                              ║"
  echo "║     $ui_url                                       ║"
  echo "║                                                                 ║"
  echo "║  🛠️  Configure the Python SDK:                                   ║"
  echo "║     \$ python --version                                          ║"
  echo "║     \$ pip install opik                                          ║"
  echo "║     \$ opik configure --use-local                                ║"
  echo "║                                                                 ║"
  echo "║  📚 Documentation: https://www.comet.com/docs/opik/             ║"
  echo "║                                                                 ║"
  echo "║  💬 Need help? Join our community: https://chat.comet.com       ║"
  echo "║                                                                 ║"
  echo "╚═════════════════════════════════════════════════════════════════╝"
}

# Check installation
send_install_report() {
  uuid="$1"
  event_completed="$2"  # Pass "true" to send opik_os_install_completed
  start_time="$3"  # Optional: start time in ISO 8601 format

  if [ "$OPIK_USAGE_REPORT_ENABLED" != "true" ] && [ "$OPIK_USAGE_REPORT_ENABLED" != "" ]; then
    [[ "$DEBUG_MODE" == true ]] && echo "[DEBUG] Usage reporting is disabled. Skipping install report."
    return
  fi

  INSTALL_MARKER_FILE="$script_dir/.opik_install_reported"

  if [ -f "$INSTALL_MARKER_FILE" ]; then
    [[ "$DEBUG_MODE" == true ]] && echo "[DEBUG] Install report already sent; skipping."
    return
  fi

  # Check if either curl or wget is available
  if command -v curl >/dev/null 2>&1; then
    HTTP_TOOL="curl"
  elif command -v wget >/dev/null 2>&1; then
    HTTP_TOOL="wget"
  else
    [[ "$DEBUG_MODE" == true ]] && echo "[WARN] Neither curl nor wget is available; skipping usage report."
    return
  fi

  timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

  if [ "$event_completed" = "true" ]; then

    event_type="opik_os_install_completed"
    end_time="$timestamp"
    json_payload=$(cat <<EOF
{
  "anonymous_id": "$uuid",
  "event_type": "$event_type",
  "event_properties": {
    "start_time": "$start_time",
    "end_time": "$end_time",
    "script_type": "sh"
  }
}
EOF
)
  else
    event_type="opik_os_install_started"
    json_payload=$(cat <<EOF
{
  "anonymous_id": "$uuid",
  "event_type": "$event_type",
  "event_properties": {
    "start_time": "$start_time",
    "script_type": "sh"
  }
}
EOF
)
  fi

  url="https://stats.comet.com/notify/event/"

  if [ "$HTTP_TOOL" = "curl" ]; then
    curl -s -X POST -H "Content-Type: application/json" -d "$json_payload" "$url" >/dev/null 2>&1
  else
    tmpfile=$(mktemp)
    echo "$json_payload" > "$tmpfile"
    wget --quiet --method POST --header="Content-Type: application/json" --body-file="$tmpfile" -O /dev/null "$url"
    rm -f "$tmpfile"
  fi

  if [ $event_type = "opik_os_install_completed" ]; then
    touch "$INSTALL_MARKER_FILE"
    [[ "$DEBUG_MODE" == true ]] && echo "[DEBUG] Post-install report sent successfully."
  else
    [[ "$DEBUG_MODE" == true ]] && echo "[DEBUG] Install started report sent successfully."
  fi
}

# Default: no build
BUILD_MODE=
# Default: no debug
DEBUG_MODE=false
# Default: no port mapping
PORT_MAPPING=false
# Default: no guardrails
GUARDRAILS_ENABLED=false
# Default: no local development
LOCAL_MODE=false
export TOGGLE_GUARDRAILS_ENABLED=false
export OPIK_FRONTEND_FLAVOR=default

if [[ "$*" == *"--build"* ]]; then
  BUILD_MODE=true
  # Remove the flag from arguments
  set -- ${@/--build/}
fi

if [[ "$*" == *"--debug"* ]]; then
  DEBUG_MODE=true
  echo "🐞 Debug mode enabled."
  # Remove the flag from arguments
  set -- ${@/--debug/}
fi

if [[ "$*" == *"--port-mapping"* ]]; then
  PORT_MAPPING=true
  # Remove the flag from arguments
  set -- ${@/--port-mapping/}
fi

# Check for guardrails flag first
if [[ "$*" == *"--guardrails"* ]]; then
  GUARDRAILS_ENABLED=true
  CONTAINERS+=("${GUARDRAILS_CONTAINERS[@]}")
  export OPIK_FRONTEND_FLAVOR=guardrails
  export TOGGLE_GUARDRAILS_ENABLED=true
  # Remove the flag from arguments
  set -- ${@/--guardrails/}
fi

# Check for local development flag
if [[ "$*" == *"--local"* ]]; then
  LOCAL_MODE=true
  # Remove the flag from arguments
  set -- ${@/--local/}
fi

# Check for migrate flag (used with --local)
if [[ "$*" == *"--migrate"* ]]; then
  RUN_MIGRATIONS=true
  # Remove the flag from arguments
  set -- ${@/--migrate/}
fi

# Main logic
case "$1" in
  --verify)
    echo "🔍 Verifying container health..."
    check_containers_status "true"
    exit $?
    ;;
  --info)
    echo "ℹ️  Checking if all containers are up before displaying system status..."
    if check_containers_status "true"; then
      print_banner
      exit 0
    else
      echo "⚠️  Some containers are not running/healthy. Please run '$(get_start_cmd)' to start them."
      exit 1
    fi
    ;;
  --stop)
    stop_containers
    exit 0
    ;;
  --help)
    print_usage
    exit 0
    ;;
  "")
    # Handle local development mode
    if [[ "$LOCAL_MODE" == "true" ]]; then
      run_local_development
      exit 0
    fi
    
    # Original logic for normal mode
    echo "🔍 Checking container status and starting missing ones..."
    start_missing_containers
    sleep 2
    echo "🔄 Re-checking container status..."
    if check_containers_status; then
      print_banner
    else
      echo "⚠️  Some containers are still not healthy. Please check manually using '$(get_verify_cmd)'"
      exit 1
    fi
    ;;
  *)
    echo "❌ Unknown option: $1"
    print_usage
    exit 1
    ;;
esac
