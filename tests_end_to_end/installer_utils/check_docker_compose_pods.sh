#!/bin/bash

# Determine the project name by checking what's actually running
# This handles both opik.sh (uses worktree-based naming) and direct docker compose (uses "opik")
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"

# Try to find a running opik project by checking known project name patterns
find_opik_project() {
    # Get list of running compose projects
    local projects
    projects=$(docker compose ls --format json 2>/dev/null | grep -o '"Name":"[^"]*"' | cut -d'"' -f4)
    
    # First, try worktree-based name (opik.sh style)
    if [[ -f "$repo_root/scripts/worktree-utils.sh" ]]; then
        WORKTREE_UTILS_ROOT="$repo_root"
        source "$repo_root/scripts/worktree-utils.sh"
        init_worktree_ports
        local worktree_name="$COMPOSE_PROJECT_NAME"
        if echo "$projects" | grep -q "^${worktree_name}$"; then
            echo "$worktree_name"
            return
        fi
    fi
    
    # Then try "opik" (direct docker compose style)
    if echo "$projects" | grep -q "^opik$"; then
        echo "opik"
        return
    fi
    
    # Finally, try any project starting with "opik-"
    local opik_project
    opik_project=$(echo "$projects" | grep "^opik" | head -1)
    if [[ -n "$opik_project" ]]; then
        echo "$opik_project"
        return
    fi
    
    # Default fallback
    if [[ -f "$repo_root/scripts/worktree-utils.sh" ]]; then
        echo "$COMPOSE_PROJECT_NAME"
    else
        echo "opik"
    fi
}

PROJECT_NAME=$(find_opik_project)

echo "Using Docker Compose project name: $PROJECT_NAME"
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
