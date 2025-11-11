#!/bin/bash
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INFRA_CONTAINERS=("opik-clickhouse-1" "opik-mysql-1" "opik-redis-1" "opik-minio-1" "opik-zookeeper-1")
BACKEND_CONTAINERS=("opik-python-backend-1" "opik-backend-1")
OPIK_CONTAINERS=("opik-frontend-1")
GUARDRAILS_CONTAINERS=("opik-guardrails-backend-1")
LOCAL_BE_CONTAINERS=("opik-python-backend-1" "opik-frontend-1")
LOCAL_BE_FE_CONTAINERS=("opik-python-backend-1")

# Bash doesn't have straight forward support for returning arrays, so using a global var instead
CONTAINERS=()

set_containers_for_profile() {
  if [[ "$INFRA" == "true" ]]; then
    CONTAINERS=("${INFRA_CONTAINERS[@]}")
  elif [[ "$BACKEND" == "true" ]]; then
    CONTAINERS=("${INFRA_CONTAINERS[@]}" "${BACKEND_CONTAINERS[@]}")
  elif [[ "$LOCAL_BE" == "true" ]]; then
    CONTAINERS=("${INFRA_CONTAINERS[@]}" "${LOCAL_BE_CONTAINERS[@]}")
  elif [[ "$LOCAL_BE_FE" == "true" ]]; then
    CONTAINERS=("${INFRA_CONTAINERS[@]}" "${LOCAL_BE_FE_CONTAINERS[@]}")
  else
    # Full Opik (default)
    CONTAINERS=("${INFRA_CONTAINERS[@]}" "${BACKEND_CONTAINERS[@]}" "${OPIK_CONTAINERS[@]}")
  fi
  
  # Add guardrails containers if enabled
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    CONTAINERS+=("${GUARDRAILS_CONTAINERS[@]}")
  fi
}

get_verify_cmd() {
  local cmd="./opik.sh"
  if [[ "$INFRA" == "true" ]]; then
    cmd="$cmd --infra"
  elif [[ "$BACKEND" == "true" ]]; then
    cmd="$cmd --backend"
  elif [[ "$LOCAL_BE" == "true" ]]; then
    cmd="$cmd --local-be"
  elif [[ "$LOCAL_BE_FE" == "true" ]]; then
    cmd="$cmd --local-be-fe"
  fi
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
  if [[ "$INFRA" == "true" ]]; then
    cmd="$cmd --infra"
  elif [[ "$BACKEND" == "true" ]]; then
    cmd="$cmd --backend"
  elif [[ "$LOCAL_BE" == "true" ]]; then
    cmd="$cmd --local-be"
  elif [[ "$LOCAL_BE_FE" == "true" ]]; then
    cmd="$cmd --local-be-fe"
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

debugLog() {
  [[ "$DEBUG_MODE" == true ]] && echo "$@"
}

setup_buildx_bake() {
  if [[ "${BUILD_MODE}" = "true" ]]; then
    if docker buildx bake --help >/dev/null 2>&1; then
      echo "ℹ️ Bake is available on Docker Buildx. Exporting COMPOSE_BAKE=true"
      export COMPOSE_BAKE=true
    else
      echo "ℹ️ Bake is not available on Docker Buildx. Not using it for builds"
    fi
  fi
}

get_system_info() {
  # Function to gather system info without failing the script
  # All commands wrapped with error handling and fallbacks
  
  # OS detection - safe with fallback
  local os_info="unknown"
  if command -v uname >/dev/null 2>&1; then
    os_info=$(uname -s 2>/dev/null || echo "unknown")
    if [[ "$os_info" == "Darwin" ]]; then
      local os_version=$(sw_vers -productVersion 2>/dev/null || echo "")
      [[ -n "$os_version" ]] && os_info="macOS ${os_version}" || os_info="macOS"
    elif [[ "$os_info" == "Linux" ]]; then
      if [[ -f /etc/os-release ]]; then
        local distro=$(grep -E '^PRETTY_NAME=' /etc/os-release 2>/dev/null | cut -d= -f2 | tr -d '"' || echo "Linux")
        [[ -n "$distro" ]] && os_info="$distro" || os_info="Linux"
      fi
    fi
  fi
  
  # Docker version - safe with fallback
  local docker_version="unknown"
  if command -v docker >/dev/null 2>&1; then
    local docker_output=$(docker --version 2>/dev/null || echo "")
    if [[ -n "$docker_output" ]]; then
      # Extract version: "Docker version 26.1.4, build..." -> "26.1.4"
      docker_version=$(echo "$docker_output" | sed -n 's/^Docker version \([^,]*\).*/\1/p' || echo "unknown")
      [[ -z "$docker_version" ]] && docker_version="unknown"
    fi
  fi
  
  # Docker Compose version - safe with fallback
  # Try both V2 (docker compose) and V1 (docker-compose) commands
  local docker_compose_version="unknown"
  if command -v docker >/dev/null 2>&1; then
    # Try Docker Compose V2 (plugin)
    local compose_output=$(docker compose version 2>/dev/null || echo "")
    if [[ -n "$compose_output" ]]; then
      # Extract version: "Docker Compose version v2.27.1-desktop.1" -> "v2.27.1-desktop.1"
      docker_compose_version=$(echo "$compose_output" | sed -n 's/^Docker Compose version \(.*\)$/\1/p' || echo "unknown")
      [[ -z "$docker_compose_version" ]] && docker_compose_version="unknown"
    fi
  fi
  
  # If V2 failed, try Docker Compose V1 (standalone)
  if [[ "$docker_compose_version" == "unknown" ]] && command -v docker-compose >/dev/null 2>&1; then
    docker_compose_version=$(docker-compose version --short 2>/dev/null || echo "unknown")
  fi
  
  # Return as tab-delimited string (tabs are extremely unlikely in version strings)
  printf "%s\t%s\t%s" "$os_info" "$docker_version" "$docker_compose_version"
}

get_docker_compose_cmd() {
  local cmd="docker compose -f $script_dir/deployment/docker-compose/docker-compose.yaml"
  if [[ "$PORT_MAPPING" == "true" ]]; then
    cmd="$cmd -f $script_dir/deployment/docker-compose/docker-compose.override.yaml"
  fi

  # Add profiles based on the selected mode (accumulative)
  if [[ "$INFRA" == "true" ]]; then
    # No profile needed - infrastructure services start by default
    :
  elif [[ "$BACKEND" == "true" ]]; then
    cmd="$cmd --profile backend"
  elif [[ "$LOCAL_BE" == "true" ]]; then
    cmd="$cmd -f $script_dir/deployment/docker-compose/docker-compose.local-be.yaml"
    cmd="$cmd --profile local-be"
  elif [[ "$LOCAL_BE_FE" == "true" ]]; then
    cmd="$cmd -f $script_dir/deployment/docker-compose/docker-compose.local-be-fe.yaml"
    cmd="$cmd --profile local-be-fe"
  else
    # Full Opik (default) - includes all dependencies
    cmd="$cmd --profile opik"
  fi
  
  # Always add guardrails profile if enabled
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    cmd="$cmd --profile guardrails"
  fi
  
  echo "$cmd"
}

get_ui_url() {
  local frontend_port=$(docker inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>/dev/null)
  echo "http://localhost:${frontend_port:-5173}"
}

create_opik_config_if_missing() {
  local config_file="$HOME/.opik.config"
  
  if [[ -f "$config_file" ]]; then
    debugLog "[DEBUG] .opik.config file already exists, skipping creation"
    return
  fi
  
  debugLog "[DEBUG] Creating .opik.config file at $config_file"
  
  local ui_url=$(get_ui_url)
  
  cat > "$config_file" << EOF
[opik]
url_override = ${ui_url}/api/
workspace = default
EOF
  debugLog "[DEBUG] .opik.config file created successfully with URL: ${ui_url}/api/"
}

print_usage() {
  echo "Usage: opik.sh [OPTIONS]"
  echo ""
  echo "Options:"
  echo "  --verify        Check if all containers are healthy"
  echo "  --info          Display welcome system status, only if all containers are running"
  echo "  --stop          Stop all containers and clean up"
  echo "  --clean         Stop all containers and remove all Opik data volumes (WARNING: ALL OPIK DATA WILL BE LOST)"
  echo "  --demo-data     Triggers creation of demo data, assumes all required services (backend, python-backend, frontend etc.) are already running"
  echo "  --build         Build containers before starting (can be combined with other flags)"
  echo "  --debug         Enable debug mode (verbose output) (can be combined with other flags)"
  echo "  --port-mapping  Enable port mapping for all containers by using the override file (can be combined with other flags)"
  echo "  --infra         Start only infrastructure services (MySQL, Redis, ClickHouse, ZooKeeper, MinIO etc.)"
  echo "  --backend       Start only infrastructure + backend services (Backend, Python Backend etc.)"
  echo "  --local-be      Start all services EXCEPT backend (for local backend development)"
  echo "  --local-be-fe   Start only infrastructure + Python backend (for local backend + frontend development)"
  echo "  --guardrails    Enable guardrails profile (can be combined with other flags)"
  echo "  --help          Show this help message"
  echo ""
  echo "If no option is passed, the script will start missing containers and then show the system status."
}

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

# Wait for a container to complete and return its exit code
# Args: $1 = container name, $2 = timeout in seconds (default: 60)
# Returns: 0 if container exits with code 0, 1 otherwise
wait_for_container_completion() {
  local container_name="$1"
  local max_wait="${2:-60}"
  local count=0

  debugLog "[DEBUG] Waiting for $container_name to complete (timeout: ${max_wait}s)..."

  while [ $count -lt "$max_wait" ]; do
    local status
    status=$(docker inspect -f '{{.State.Status}}' "$container_name" 2>/dev/null || echo "not_found")

    if [ "$status" = "exited" ]; then
      local exit_code
      exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$container_name" 2>/dev/null || echo "1")
      debugLog "[DEBUG] $container_name exited with code: $exit_code"
      return "$exit_code"
    elif [ "$status" = "not_found" ]; then
      echo "❌ $container_name container not found"
      return 1
    fi

    sleep 1
    count=$((count + 1))
  done

  echo "❌ Timeout waiting for $container_name to complete"
  docker logs "$container_name" 2>/dev/null || true
  return 1
}

start_missing_containers() {
  check_docker_status

  # Generate a run-scoped anonymous ID for this installation session
  uuid=$(generate_uuid)
  start_time=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  # Export persistent install UUID so docker-compose and services can consume it
  export OPIK_ANONYMOUS_ID="$uuid"
  send_install_report "$uuid" "false" "$start_time"
  
  debugLog "OPIK_ANONYMOUS_ID=$uuid"

  debugLog "🔍 Checking required containers..."
  all_running=true

  local containers=("${CONTAINERS[@]}")
  for container in "${containers[@]}"; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)

    if [[ "$status" != "running" ]]; then
      debugLog "🔴 $container is not running (status: ${status:-not found})"
      all_running=false
    else
      debugLog "✅ $container is already running"
    fi
  done

  echo "🔄 Starting missing containers..."

  setup_buildx_bake

  local cmd
  cmd=$(get_docker_compose_cmd)
  $cmd up -d ${BUILD_MODE:+--build}

  echo "⏳ Waiting for all containers to be running and healthy..."
  max_retries=60
  interval=1
  all_running=true

  for container in "${containers[@]}"; do
    retries=0
    debugLog "⏳ Waiting for $container..."

    while true; do
      status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)
      health=$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null)

      if [[ "$status" != "running" ]]; then
        echo "❌ $container failed to start (status: $status)"
        break
      fi

      if [[ "$health" == "healthy" ]]; then
        debugLog "✅ $container is now running and healthy!"
        break
      elif [[ "$health" == "starting" ]]; then
        debugLog "⏳ $container is starting... retrying (${retries}s)"
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
    create_opik_config_if_missing
  fi
}

stop_containers() {
  check_docker_status
  echo "🛑 Stopping all required containers..."
  local cmd
  cmd=$(get_docker_compose_cmd)
  $cmd down
  echo "✅ All containers stopped and cleaned up!"
}

clean_data() {
  check_docker_status
  echo "⚠️  WARNING: This will remove ALL Opik data including:"
  echo "   - MySQL (projects, datasets etc.)"
  echo "   - ClickHouse (traces, spans, etc.)"
  echo "   - Etc."
  echo ""
  echo "🗑️  Stopping all containers and removing volumes..."
  local cmd
  cmd="$(get_docker_compose_cmd) down -v"
  debugLog "[DEBUG] Running: $cmd"
  $cmd
  echo "✅ All containers stopped and data volumes removed!"
}

create_demo_data() {
  check_docker_status
  echo "📊 Creating demo data..."

  setup_buildx_bake

  # Build the complete command once
  # --no-deps: Don't start dependent services
  # ${BUILD_MODE:+--build}: Add --build flag if BUILD_MODE is set
  local cmd
  cmd="$(get_docker_compose_cmd) up --no-deps -d ${BUILD_MODE:+--build} demo-data-generator"
  
  debugLog "[DEBUG] Running: $cmd"
  if ! $cmd; then
    echo "❌ Failed to start demo-data-generator"
    return 1
  fi
  
  # Wait for the container to finish and check its exit code
  if wait_for_container_completion "opik-demo-data-generator-1"; then
    echo "✅ Demo data created successfully!"
    return 0
  else
    echo "❌ Failed to create demo data"
    return 1
  fi
}

print_banner() {
  check_docker_status
  ui_url=$(get_ui_url)

  echo ""
  echo "╔═════════════════════════════════════════════════════════════════╗"
  echo "║                                                                 ║"
  echo "║                       🚀 OPIK PLATFORM 🚀                       ║"
  echo "║                                                                 ║"
  echo "╠═════════════════════════════════════════════════════════════════╣"
  echo "║                                                                 ║"
  if [[ "$GUARDRAILS_ENABLED" == "true" ]]; then
    echo "║  ✅ Guardrails services started successfully!                   ║"
  fi
  if [[ "$INFRA" == "true" ]]; then
    echo "║  ✅ Infrastructure services started successfully!               ║"
    echo "║                                                                 ║"
  elif [[ "$BACKEND" == "true" ]]; then
    echo "║  ✅ Backend services started successfully!                      ║"
    echo "║                                                                 ║"
  elif [[ "$LOCAL_BE_FE" == "true" ]]; then
    echo "║  ✅ Local backend + frontend mode services started!             ║"
    echo "║                                                                 ║"
    echo "║  ⚙️  Configuration:                                              ║"
    echo "║     Backend is NOT running in Docker                            ║"
    echo "║     Frontend is NOT running in Docker                           ║"
    echo "║     Port mapping: ENABLED (required for local processes)        ║"
    echo "║                                                                 ║"
    echo "║  📊 Access the UI (start backend + frontend first):             ║"
    echo "║     http://localhost:5174                                       ║"
    echo "║                                                                 ║"
  elif [[ "$LOCAL_BE" == "true" ]]; then
    echo "║  ✅ Local backend mode services started successfully!           ║"
    echo "║                                                                 ║"
    echo "║  ⚙️  Backend Configuration:                                      ║"
    echo "║     Backend is NOT running in Docker                            ║"
    echo "║     Start your local backend on port 8080                       ║"
    echo "║     Frontend will proxy to: http://localhost:8080               ║"
    echo "║     Port mapping: ENABLED (required for local processes)        ║"
    echo "║                                                                 ║"
    echo "║  📊 Access the UI (start backend first):                        ║"
    echo "║     $ui_url                                       ║"
    echo "║                                                                 ║"
  else
    echo "║  ✅ All services started successfully!                          ║"
    echo "║                                                                 ║"
    echo "║  📊 Access the UI:                                              ║"
    echo "║     $ui_url                                       ║"
    echo "║                                                                 ║"
    echo "║  🛠️  Install the Python SDK:                                     ║"
    echo "║     \$ python --version                                          ║"
    echo "║     \$ pip install opik                                          ║"
  fi
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

  # Configure usage reporting based on deployment mode
  # $PROFILE_COUNT: if > 0, it's a partial profile; if = 0, it's full Opik
  if [[ $PROFILE_COUNT -gt 0 ]]; then
    # Partial profile mode - disable reporting
    export OPIK_USAGE_REPORT_ENABLED=false
    debugLog "[DEBUG] Disabling usage reporting due to not starting the full Opik suite"
  fi

  if [ "$OPIK_USAGE_REPORT_ENABLED" != "true" ] && [ "$OPIK_USAGE_REPORT_ENABLED" != "" ]; then
    debugLog "[DEBUG] Usage reporting is disabled. Skipping install report."
    return
  fi

  INSTALL_MARKER_FILE="$script_dir/.opik_install_reported"

  if [ -f "$INSTALL_MARKER_FILE" ]; then
    debugLog "[DEBUG] Install report already sent; skipping."
    return
  fi

  # Check if either curl or wget is available
  if command -v curl >/dev/null 2>&1; then
    HTTP_TOOL="curl"
  elif command -v wget >/dev/null 2>&1; then
    HTTP_TOOL="wget"
  else
    debugLog "[WARN] Neither curl nor wget is available; skipping usage report."
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
    "event_ver": "1",
    "script_type": "sh"
  }
}
EOF
)
  else
    event_type="opik_os_install_started"
    
    # Get system info safely - wrapped to prevent script failure
    system_info=$(get_system_info 2>/dev/null || printf "unknown\tunknown\tunknown")
    IFS=$'\t' read -r os_info docker_ver docker_compose_ver <<< "$system_info"
    
    debugLog "[DEBUG] System info: OS=$os_info, Docker=$docker_ver, Docker Compose=$docker_compose_ver"
    
    json_payload=$(cat <<EOF
{
  "anonymous_id": "$uuid",
  "event_type": "$event_type",
  "event_properties": {
    "start_time": "$start_time",
    "event_ver": "1",
    "script_type": "sh",
    "os": "$os_info",
    "docker_version": "$docker_ver",
    "docker_compose_version": "$docker_compose_ver"
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
    debugLog "[DEBUG] Post-install report sent successfully."
  else
    debugLog "[DEBUG] Install started report sent successfully."
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
export TOGGLE_GUARDRAILS_ENABLED=false
export OPIK_FRONTEND_FLAVOR=default
# Default: full opik (all profiles)
INFRA=false
BACKEND=false
LOCAL_BE=false
LOCAL_BE_FE=false

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

# Check for profile flags
if [[ "$*" == *"--infra"* ]]; then
  INFRA=true
  # Remove the flag from arguments
  set -- ${@/--infra/}
fi

if [[ "$*" == *"--backend"* ]]; then
  BACKEND=true
  # Enable CORS for frontend development
  export CORS=true
  # Remove the flag from arguments
  set -- ${@/--backend/}
fi

# Check --local-be-fe BEFORE --local-be (more specific first or regex will cause a script failure)
if [[ "$*" == *"--local-be-fe"* ]]; then
  LOCAL_BE_FE=true
  PORT_MAPPING=true  # Required for local processes to connect to infrastructure
  export OPIK_REVERSE_PROXY_URL="http://host.docker.internal:8080"
  # Remove the flag from arguments
  set -- ${@/--local-be-fe/}
fi

if [[ "$*" == *"--local-be"* ]]; then
  LOCAL_BE=true
  PORT_MAPPING=true  # Required for local processes to connect to infrastructure
  export OPIK_FRONTEND_FLAVOR=local_be
  # Remove the flag from arguments
  set -- ${@/--local-be/}
fi

# Check for guardrails flag
if [[ "$*" == *"--guardrails"* ]]; then
  GUARDRAILS_ENABLED=true
  # Only override flavor if not already set by local-be
  if [[ "$OPIK_FRONTEND_FLAVOR" == "default" ]]; then
    export OPIK_FRONTEND_FLAVOR=guardrails
  fi
  export TOGGLE_GUARDRAILS_ENABLED=true
  # Remove the flag from arguments
  set -- ${@/--guardrails/}
fi

# Count active partial profiles
PROFILE_COUNT=0
[[ "$INFRA" == "true" ]] && ((PROFILE_COUNT++))
[[ "$BACKEND" == "true" ]] && ((PROFILE_COUNT++))
[[ "$LOCAL_BE" == "true" ]] && ((PROFILE_COUNT++))
[[ "$LOCAL_BE_FE" == "true" ]] && ((PROFILE_COUNT++))

# Validate mutually exclusive profile flags
if [[ $PROFILE_COUNT -gt 1 ]]; then
  echo "❌ Error: --infra, --backend, --local-be, and --local-be-fe flags are mutually exclusive."
  echo "   Choose one of the following:"
  echo "   • ./opik.sh --infra        (infrastructure services only)"
  echo "   • ./opik.sh --backend      (infrastructure + backend services)"
  echo "   • ./opik.sh --local-be     (all services except backend - for local backend development)"
  echo "   • ./opik.sh --local-be-fe  (infrastructure + Python backend - for local BE+FE development)"
  echo "   • ./opik.sh                (full Opik suite - default)"
  exit 1
fi

# Set containers based on the selected profile
set_containers_for_profile

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
    exit $?
    ;;
  --clean)
    clean_data
    exit $?
    ;;
  --demo-data)
    create_demo_data
    exit $?
    ;;
  --help)
    print_usage
    exit 0
    ;;
  "")
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
