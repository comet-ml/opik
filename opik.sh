#!/bin/bash
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REQUIRED_CONTAINERS=("opik-clickhouse-1" "opik-mysql-1" "opik-python-backend-1" "opik-redis-1" "opik-frontend-1" "opik-backend-1" "opik-minio-1" "opik-zookeeper-1")
GUARDRAILS_CONTAINERS=("opik-guardrails-backend-1")

# Bash doesn't have straight forward support for returning arrays, so using a global var instead
CONTAINERS=("${REQUIRED_CONTAINERS[@]}")

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
  echo "║     \$ opik configure                                            ║"
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
