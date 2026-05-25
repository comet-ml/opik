#!/bin/bash

# Shared Worktree Utilities for Multi-Worktree Development
# This file provides common functions for worktree detection and port calculation
# Used by both dev-runner.sh and opik.sh to ensure consistent behavior

# Get the project root directory (caller should set WORKTREE_UTILS_ROOT before sourcing)
# Falls back to the directory of this script's parent
WORKTREE_PROJECT_ROOT="${WORKTREE_UTILS_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

# Extract worktree name from git (uses directory name if not in a worktree)
# Returns: sanitized directory name suitable for Docker project names
get_worktree_id() {
    local worktree_path
    worktree_path=$(git -C "$WORKTREE_PROJECT_ROOT" rev-parse --show-toplevel 2>/dev/null)

    if [ -z "$worktree_path" ]; then
        worktree_path="$WORKTREE_PROJECT_ROOT"
    fi

    # Sanitize for Docker: lowercase, alphanumeric, hyphens only
    local name
    name=$(basename "$worktree_path" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9-]/-/g' | sed 's/--*/-/g' | sed 's/^-//;s/-$//')

    if [ -z "$name" ]; then
        name="default"
    fi

    echo "$name"
}

# Check if we're in a git worktree (not the main repo)
# Returns: 0 if worktree, 1 if main repo
is_git_worktree() {
    local git_dir="$WORKTREE_PROJECT_ROOT/.git"
    # In a worktree, .git is a file; in main repo, .git is a directory
    [ -f "$git_dir" ]
}

# Calculate deterministic port offset based on path hash
# Returns: 0-99 for port offset (0 for main repo, calculated for worktrees)
calculate_port_offset() {
    # Allow manual override
    if [ -n "${OPIK_PORT_OFFSET:-}" ]; then
        echo "$OPIK_PORT_OFFSET"
        return
    fi

    # Main repo uses offset 0 for backward compatibility
    if ! is_git_worktree; then
        echo "0"
        return
    fi

    # Calculate offset from path hash
    local hash_input="$WORKTREE_PROJECT_ROOT"
    local hash_hex

    if command -v md5 &>/dev/null; then
        hash_hex=$(echo -n "$hash_input" | md5)
    elif command -v md5sum &>/dev/null; then
        hash_hex=$(echo -n "$hash_input" | md5sum | cut -d' ' -f1)
    else
        # Fallback: simple character-based hash
        local sum=0
        for (( i=0; i<${#hash_input}; i++ )); do
            sum=$(( (sum + $(printf '%d' "'${hash_input:$i:1}")) % 100 ))
        done
        echo "$sum"
        return
    fi

    # Convert first 4 hex chars to decimal mod 100
    local first_four="${hash_hex:0:4}"
    local decimal=$((16#$first_four))
    echo $((decimal % 100))
}

# Base ports (standard Opik ports)
readonly BASE_BACKEND_PORT=8080
readonly BASE_BACKEND_ADMIN_PORT=8081
readonly BASE_NGINX_PORT=5173
readonly BASE_FRONTEND_DEV_PORT=5174
readonly BASE_MYSQL_PORT=3306
readonly BASE_REDIS_PORT=6379
readonly BASE_CLICKHOUSE_HTTP_PORT=8123
readonly BASE_CLICKHOUSE_NATIVE_PORT=9000
readonly BASE_PYTHON_BACKEND_PORT=8000
readonly BASE_ZOOKEEPER_PORT=2181
readonly BASE_MINIO_API_PORT=9001
readonly BASE_MINIO_CONSOLE_PORT=9090

# Detect and validate container runtime (docker or podman).
# Sets CONTAINER_RUNTIME, COMPOSE_CMD, OPIK_HOST_GATEWAY and exports all three.
# Honors CONTAINER_RUNTIME if already set by caller (e.g. --runtime flag).
resolve_container_runtime() {
    if [[ -n "${CONTAINER_RUNTIME:-}" ]]; then
        if [[ "$CONTAINER_RUNTIME" != "docker" && "$CONTAINER_RUNTIME" != "podman" ]]; then
            echo "❌ Invalid runtime '$CONTAINER_RUNTIME'. Must be 'docker' or 'podman'."
            exit 1
        fi
        if ! command -v "$CONTAINER_RUNTIME" >/dev/null 2>&1; then
            echo "❌ Runtime '$CONTAINER_RUNTIME' was requested but is not installed or not on PATH."
            exit 1
        fi
    else
        if docker info >/dev/null 2>&1; then
            CONTAINER_RUNTIME="docker"
        elif podman info >/dev/null 2>&1; then
            CONTAINER_RUNTIME="podman"
        else
            echo "❌ Neither Docker nor Podman is available. Please install one first."
            exit 1
        fi
    fi

    if [[ "$CONTAINER_RUNTIME" == "podman" ]]; then
        if podman compose version >/dev/null 2>&1; then
            COMPOSE_CMD="podman compose"
        elif command -v podman-compose >/dev/null 2>&1; then
            COMPOSE_CMD="podman-compose"
        else
            echo "❌ Podman found but no compose tool available."
            echo "   Option 1: Upgrade to Podman 4.7+ (includes 'podman compose')"
            echo "   Option 2: pip install podman-compose>=1.0"
            exit 1
        fi
        export OPIK_HOST_GATEWAY="host.containers.internal"
    else
        COMPOSE_CMD="docker compose"
        export OPIK_HOST_GATEWAY="host.docker.internal"
    fi

    export CONTAINER_RUNTIME COMPOSE_CMD
}

# Initialize worktree variables
# Call this function after sourcing to set up all port variables
init_worktree_ports() {
    WORKTREE_ID=$(get_worktree_id)
    PORT_OFFSET=$(calculate_port_offset)
    RESOURCE_PREFIX="opik-${WORKTREE_ID}"

    # Calculate ports — pre-set env vars win, so users can pin a single port
    # (e.g. NGINX_PORT=5293) without disabling the worktree offset for the rest.
    BACKEND_PORT=${BACKEND_PORT:-$((BASE_BACKEND_PORT + PORT_OFFSET))}
    BACKEND_ADMIN_PORT=${BACKEND_ADMIN_PORT:-$((BASE_BACKEND_ADMIN_PORT + PORT_OFFSET))}
    NGINX_PORT=${NGINX_PORT:-$((BASE_NGINX_PORT + PORT_OFFSET))}
    FRONTEND_PORT=${FRONTEND_PORT:-$((BASE_FRONTEND_DEV_PORT + PORT_OFFSET))}
    MYSQL_PORT=${MYSQL_PORT:-$((BASE_MYSQL_PORT + PORT_OFFSET))}
    REDIS_PORT=${REDIS_PORT:-$((BASE_REDIS_PORT + PORT_OFFSET))}
    CLICKHOUSE_HTTP_PORT=${CLICKHOUSE_HTTP_PORT:-$((BASE_CLICKHOUSE_HTTP_PORT + PORT_OFFSET))}
    CLICKHOUSE_NATIVE_PORT=${CLICKHOUSE_NATIVE_PORT:-$((BASE_CLICKHOUSE_NATIVE_PORT + PORT_OFFSET))}
    PYTHON_BACKEND_PORT=${PYTHON_BACKEND_PORT:-$((BASE_PYTHON_BACKEND_PORT + PORT_OFFSET))}
    ZOOKEEPER_PORT=${ZOOKEEPER_PORT:-$((BASE_ZOOKEEPER_PORT + PORT_OFFSET))}
    MINIO_API_PORT=${MINIO_API_PORT:-$((BASE_MINIO_API_PORT + PORT_OFFSET))}
    MINIO_CONSOLE_PORT=${MINIO_CONSOLE_PORT:-$((BASE_MINIO_CONSOLE_PORT + PORT_OFFSET))}

    # Export for child processes (docker-compose, etc.)
    export OPIK_BACKEND_PORT="$BACKEND_PORT"
    export OPIK_FRONTEND_PORT="$NGINX_PORT"
    export NGINX_PORT
    export FRONTEND_PORT
    export MYSQL_PORT
    export REDIS_PORT
    export CLICKHOUSE_HTTP_PORT
    export CLICKHOUSE_NATIVE_PORT
    export PYTHON_BACKEND_PORT
    export ZOOKEEPER_PORT
    export MINIO_API_PORT
    export MINIO_CONSOLE_PORT
    export COMPOSE_PROJECT_NAME="$RESOURCE_PREFIX"
}
