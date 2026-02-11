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
readonly BASE_OPIK_AI_BACKEND_PORT=8081

# Initialize worktree variables
# Call this function after sourcing to set up all port variables
init_worktree_ports() {
    WORKTREE_ID=$(get_worktree_id)
    PORT_OFFSET=$(calculate_port_offset)
    RESOURCE_PREFIX="opik-${WORKTREE_ID}"

    # Calculate ports
    BACKEND_PORT=$((BASE_BACKEND_PORT + PORT_OFFSET))
    BACKEND_ADMIN_PORT=$((BASE_BACKEND_ADMIN_PORT + PORT_OFFSET))
    NGINX_PORT=$((BASE_NGINX_PORT + PORT_OFFSET))
    FRONTEND_PORT=$((BASE_FRONTEND_DEV_PORT + PORT_OFFSET))
    MYSQL_PORT=$((BASE_MYSQL_PORT + PORT_OFFSET))
    REDIS_PORT=$((BASE_REDIS_PORT + PORT_OFFSET))
    CLICKHOUSE_HTTP_PORT=$((BASE_CLICKHOUSE_HTTP_PORT + PORT_OFFSET))
    CLICKHOUSE_NATIVE_PORT=$((BASE_CLICKHOUSE_NATIVE_PORT + PORT_OFFSET))
    PYTHON_BACKEND_PORT=$((BASE_PYTHON_BACKEND_PORT + PORT_OFFSET))
    ZOOKEEPER_PORT=$((BASE_ZOOKEEPER_PORT + PORT_OFFSET))
    MINIO_API_PORT=$((BASE_MINIO_API_PORT + PORT_OFFSET))
    MINIO_CONSOLE_PORT=$((BASE_MINIO_CONSOLE_PORT + PORT_OFFSET))
    OPIK_AI_BACKEND_PORT=$((BASE_OPIK_AI_BACKEND_PORT + PORT_OFFSET))

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
    export OPIK_AI_BACKEND_PORT
    export COMPOSE_PROJECT_NAME="$RESOURCE_PREFIX"
}
