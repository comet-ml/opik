#!/bin/bash

# Determine the project name using the same logic as opik.sh
# This ensures we look for containers started by the opik.sh script
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

# Source worktree utilities to get COMPOSE_PROJECT_NAME
if [[ -f "$repo_root/scripts/worktree-utils.sh" ]]; then
    WORKTREE_UTILS_ROOT="$repo_root"
    source "$repo_root/scripts/worktree-utils.sh"
    init_worktree_ports
    PROJECT_NAME="$COMPOSE_PROJECT_NAME"
else
    # Fallback: use "opik" as default project name
    PROJECT_NAME="opik"
fi

echo "Using Docker Compose project name: $PROJECT_NAME"
echo "Repo root: $repo_root"
echo "Available Docker Compose projects:"
docker compose ls 2>/dev/null || echo "  (none or docker compose ls failed)"

max_retries=5
wait_interval=3
retries=0

while [[ $retries -lt $max_retries ]]
do
    containers=$(docker compose -p "$PROJECT_NAME" ps --status running -q 2>/dev/null)
    if [ -z "$containers" ]; then
        echo "Waiting for containers to be up... (Attempt: $((retries+1))/$max_retries)"
        sleep $wait_interval
        retries=$((retries+1))
    else
        echo "Containers running"
        docker compose -p "$PROJECT_NAME" ps
        break
    fi
done

if [[ $retries -eq $max_retries ]]; then
    echo "Containers failed to start"
    docker compose -p "$PROJECT_NAME" ps
    exit 1
fi
