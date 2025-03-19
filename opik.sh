#!/bin/bash

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

REQUIRED_CONTAINERS=("opik-clickhouse-1" "opik-mysql-1" "opik-python-backend-1" "opik-redis-1" "opik-frontend-1" "opik-backend-1")

print_usage() {
  echo "Usage: opik.sh [OPTION]"
  echo ""
  echo "Options:"
  echo "  --verify    Check if all containers are healthy"
  echo "  --info      Display welcome system status, only if all containers are running"
  echo "  --stop      Stop all containers and clean up"
  echo "  --debug     Enable debug mode (verbose output)"
  echo "  --help      Show this help message"
  echo ""
  echo "If no option is passed, the script will start missing containers and then show the system status."
}

check_containers_status() {
  local show_ouput="${1:-false}"
  local all_ok=true

  for container in "${REQUIRED_CONTAINERS[@]}"; do
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
  [[ "$DEBUG_MODE" == true ]] && echo "🔍 Checking required containers..."
  all_running=true

  for container in "${REQUIRED_CONTAINERS[@]}"; do
    status=$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null)

    if [[ "$status" != "running" ]]; then
      [[ "$DEBUG_MODE" == true ]] && echo "🔴 $container is not running (status: ${status:-not found})"
      all_running=false
    else
      [[ "$DEBUG_MODE" == true ]] && echo "✅ $container is already running"
    fi
  done

  if [ "$all_running" = true ]; then
    echo "🚀 All required containers are already running!"
    return
  fi

  echo "🔄 Starting missing containers..."
  docker compose -f "$script_dir/deployment/docker-compose/docker-compose.yaml" up -d

  echo "⏳ Waiting for all containers to be running and healthy..."
  max_retries=60
  interval=1

  for container in "${REQUIRED_CONTAINERS[@]}"; do
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
          break
        fi
      else
        echo "❌ $container health state is '$health'"
        break
      fi
    done
  done

  echo "✅ All required containers are now running!"
}

stop_containers() {
  echo "🛑 Stopping all required containers..."
  docker compose -f $script_dir/deployment/docker-compose/docker-compose.yaml stop
  echo "✅ All containers stopped and cleaned up!"
}

print_banner() {
  frontend_port=$(docker inspect -f '{{ (index (index .NetworkSettings.Ports "5173/tcp") 0).HostPort }}' opik-frontend-1 2>/dev/null)
  ui_url="http://localhost:${frontend_port:-5173}"

  echo ""
  echo "╔═════════════════════════════════════════════════════════════════╗"
  echo "║                                                                 ║"
  echo "║                  🚀 OPIK PLATFORM 🚀                            ║"
  echo "║                                                                 ║"
  echo "╠═════════════════════════════════════════════════════════════════╣"
  echo "║                                                                 ║"
  echo "║  ✅ All services started successfully!                          ║"
  echo "║                                                                 ║"
  echo "║  📊 Access the UI:                                              ║"
  echo "║     $ui_url                                       ║"
  echo "║                                                                 ║"
  echo "║  🛠️  Configure the Python SDK:                                   ║"
  echo "║     \$ opik configure                                            ║"
  echo "║                                                                 ║"
  echo "║  📚 Documentation: https://www.comet.com/docs/opik/             ║"
  echo "║                                                                 ║"
  echo "║  💬 Need help? Join our community: https://chat.comet.com       ║"
  echo "║                                                                 ║"
  echo "╚═════════════════════════════════════════════════════════════════╝"
}

# Default: no debug
DEBUG_MODE=false

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
      echo "⚠️  Some containers are not running/healthy. Please run 'opik.sh' to start them."
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
  --debug)
    DEBUG_MODE=true
    echo "🐞 Debug mode enabled."
    echo "🔍 Checking container status and starting missing ones..."
    start_missing_containers
    sleep 2
    echo "🔄 Re-checking container status..."
    if check_containers_status; then
      print_banner
    else
      echo "⚠️  Some containers are still not healthy. Please check manually using 'opik.sh --verify'"
      exit 1
    fi
    ;;
  "")
    echo "🔍 Checking container status and starting missing ones..."
    start_missing_containers
    sleep 2
    echo "🔄 Re-checking container status..."
    if check_containers_status "true"; then
      print_banner
    else
      echo "⚠️  Some containers are still not healthy. Please check manually using 'opik.sh --verify'"
      exit 1
    fi
    ;;
  *)
    echo "❌ Unknown option: $1"
    print_usage
    exit 1
    ;;
esac
