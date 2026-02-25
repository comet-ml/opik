#!/bin/bash

set -euo pipefail

# Opik Development Runner Script

# Variables
DEBUG_MODE=${DEBUG_MODE:-false}
ORIGINAL_COMMAND="$0 $@"

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." &> /dev/null && pwd)"
BACKEND_DIR="$PROJECT_ROOT/apps/opik-backend"
FRONTEND_DIR="$PROJECT_ROOT/apps/opik-frontend"
AI_BACKEND_DIR="$PROJECT_ROOT/apps/opik-ai-backend"

# Source shared worktree utilities
WORKTREE_UTILS_ROOT="$PROJECT_ROOT"
source "$SCRIPT_DIR/worktree-utils.sh"
init_worktree_ports

# Dynamic PID and log file paths (isolated per worktree)
BACKEND_PID_FILE="/tmp/${RESOURCE_PREFIX}-backend.pid"
FRONTEND_PID_FILE="/tmp/${RESOURCE_PREFIX}-frontend.pid"
AI_BACKEND_PID_FILE="/tmp/${RESOURCE_PREFIX}-ai-backend.pid"
BACKEND_LOG_FILE="/tmp/${RESOURCE_PREFIX}-backend.log"
FRONTEND_LOG_FILE="/tmp/${RESOURCE_PREFIX}-frontend.log"
AI_BACKEND_LOG_FILE="/tmp/${RESOURCE_PREFIX}-ai-backend.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    if [ "$DEBUG_MODE" = "true" ]; then
        echo -e "${YELLOW}[DEBUG]${NC} $1"
    fi
}

require_command() {
    if ! command -v "$1" &>/dev/null; then
        log_error "Required command '$1' not found. Please install it."
        exit 1
    fi
}

# Function to find JAR files in target directory
find_jar_files() {
    local jar_files=()
    while IFS= read -r -d '' jar; do
        jar_files+=("$jar")
    done < <(find target -maxdepth 1 -type f -name 'opik-backend-*.jar' ! -name '*original*' ! -name '*sources*' ! -name '*javadoc*' -print0)
    
    if [ "${#jar_files[@]}" -eq 0 ]; then
        return 1  # No JAR files found
    elif [ "${#jar_files[@]}" -eq 1 ]; then
        JAR_FILE="${jar_files[0]}"
        log_info "Using JAR file: $JAR_FILE"
    else
        log_warning "Multiple backend JAR files found in target/:"
        for jar in "${jar_files[@]}"; do
            log_warning "  - $jar"
        done
        
        # Sort JAR files by version (assuming semantic versioning in filename)
        JAR_FILE=$(printf '%s\n' "${jar_files[@]}" | sort -V | tail -n 1)
        log_warning "Automatically selected JAR with highest version: $JAR_FILE"
        log_warning "To use a different JAR, clean up target/ directory and rebuild"
    fi
    
    return 0  # JAR file found and selected
}

# Function to start Docker services (infrastructure or infrastructure + frontend or etc.)
# Args: $1 = mode (--infra or --local-be or etc.)
start_docker_services() {
    local mode="$1"
    
    log_info "Starting Docker services..."
    cd "$PROJECT_ROOT" || { log_error "Project root directory not found"; exit 1; }
    
    if ./opik.sh "$mode"; then
        log_success "Docker services started successfully"
    else
        log_error "Failed to start Docker services"
        exit 1
    fi
}

# Function to stop Docker services (infrastructure or infrastructure + frontend or etc.)
# Args: $1 = mode (--infra or --local-be or etc.)
stop_docker_services() {
    local mode="$1"
    
    log_info "Stopping Docker services..."
    cd "$PROJECT_ROOT" || { log_error "Project root directory not found"; exit 1; }
    
    if ./opik.sh "$mode" --stop; then
        log_success "Docker services stopped"
    else
        log_warning "Failed to stop some Docker services"
    fi
}

# Function to verify Docker services
# Args: $1 = mode (--infra or --local-be or etc.)
verify_docker_services() {
    local mode="$1"
    
    cd "$PROJECT_ROOT" || { log_error "Project root directory not found"; exit 1; }
    ./opik.sh "$mode" --verify >/dev/null 2>&1
    return $?
}

start_local_be_fe() {
    start_docker_services "--local-be-fe"
}

stop_local_be_fe() {
    stop_docker_services "--local-be-fe"
}

verify_local_be_fe() {
    verify_docker_services "--local-be-fe"
}

start_local_be() {
    start_docker_services "--local-be"
}

stop_local_be() {
    stop_docker_services "--local-be"
}

verify_local_be() {
    verify_docker_services "--local-be"
}

start_local_ai() {
    start_docker_services "--local-ai"
}

stop_local_ai() {
    stop_docker_services "--local-ai"
}

verify_local_ai() {
    verify_docker_services "--local-ai"
}

# Function to build backend
build_backend() {
    require_command mvn
    log_info "Building backend (skipping tests)..."
    log_debug "Backend directory: $BACKEND_DIR"
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    # resolve.skip=true skips swagger, adjust if any future interference
    MAVEN_BUILD_CMD="mvn clean install -T 1C -Dmaven.test.skip=true -Dspotless.skip=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Dmaven.test.compile.skip=true -Dmaven.test.resources.skip=true -Dmaven.compiler.useIncrementalCompilation=false -Dresolve.skip=true"
    log_debug "Running: $MAVEN_BUILD_CMD"
    if $MAVEN_BUILD_CMD; then
        log_success "Backend build completed successfully"
    else
        log_error "Backend build failed"
        exit 1
    fi
}

# Function to build frontend
build_frontend() {
    require_command npm
    log_info "Building frontend..."
    cd "$FRONTEND_DIR" || { log_error "Frontend directory not found"; exit 1; }

    if npm install; then
        log_success "Frontend build completed successfully"
    else
        log_error "Frontend build failed"
        exit 1
    fi
}

# Function to build AI backend
build_ai_backend() {
    require_command uv
    log_info "Building AI backend..."
    log_debug "AI backend directory: $AI_BACKEND_DIR"
    cd "$AI_BACKEND_DIR" || { log_error "AI backend directory not found"; exit 1; }

    if uv sync; then
        log_success "AI backend build completed successfully"
    else
        log_error "AI backend build failed"
        exit 1
    fi
}

# Function to lint frontend
lint_frontend() {
    require_command npm
    log_info "Linting frontend..."
    cd "$FRONTEND_DIR" || { log_error "Frontend directory not found"; exit 1; }

    if npm run lint:fix; then
        log_success "Frontend linting completed successfully"
    else
        log_error "Frontend linting failed"
        exit 1
    fi

    log_info "Typechecking frontend..."
    if npm run typecheck; then
        log_success "Frontend typechecking completed successfully"
    else
        log_error "Frontend typechecking failed"
        exit 1
    fi

    log_info "Validating frontend dependencies..."
    if npm run deps:validate; then
        log_success "Frontend dependency validation completed successfully"
    else
        log_error "Frontend dependency validation failed"
        exit 1
    fi
}

# Function to lint backend
lint_backend() {
    require_command mvn
    log_info "Linting backend..."
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    if mvn spotless:apply; then
        log_success "Backend linting completed successfully"
    else
        log_error "Backend linting failed"
        exit 1
    fi
}

print_migrations_recovery_message() {
    log_error "To recover, you may need to clean up Docker volumes (WARNING: ALL DATA WILL BE LOST):"
    log_error "  1. Stop all services: $0 --stop"
    log_error "  2. Clean all data volumes (DANGER): cd $PROJECT_ROOT && ./opik.sh --clean"
    log_error "  3. Run again your current flow: $ORIGINAL_COMMAND"
}

# Function to run database migrations
run_db_migrations() {
    require_command java
    log_info "Running database migrations..."
    log_debug "Backend directory: $BACKEND_DIR"
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    # Find and validate the JAR file
    if ! find_jar_files; then
        log_warning "No backend JAR file found in target/. Building backend automatically..."
        build_backend

        # Re-scan for JAR files after build
        if ! find_jar_files; then
            log_error "Backend build completed but no JAR file found. Build may have failed."
            exit 1
        fi
    fi

    log_debug "Running migrations with JAR: $JAR_FILE"
    log_debug "Current directory: $(pwd)"

    # Run MySQL (state DB) migrations
    log_info "Running MySQL (state DB) migrations..."
    # Set the database connection environment variables for MySQL migrations (using dynamic port)
    # Note: STATE_DB_URL format is "host:port/database?params" - the jdbc:mysql:// prefix is added by config.yml
    export STATE_DB_DATABASE_NAME="opik"
    export STATE_DB_URL="localhost:${MYSQL_PORT}/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true"
    log_debug "MySQL connection: localhost:${MYSQL_PORT}"
    if java -jar "$JAR_FILE" db migrate config.yml; then
        log_success "MySQL migrations completed successfully"
    else
        # TODO: dbAnalytics clear-checksums not supported by liquibase-clickhouse yet,
        #  this would enable automatic recovery,
        #  not worthy adding it only for MySQL as volumes might need pruning anyway
        log_error "MySQL migrations failed"
        print_migrations_recovery_message
        exit 1
    fi

    # Run ClickHouse (analytics DB) migrations
    log_info "Running ClickHouse (analytics DB) migrations..."
    # Set the database name environment variable for ClickHouse migrations
    export ANALYTICS_DB_DATABASE_NAME="opik"
    # Set the connection URL (using dynamic port, no database suffix to match original behavior)
    export ANALYTICS_DB_MIGRATIONS_URL="jdbc:clickhouse://localhost:${CLICKHOUSE_HTTP_PORT}"
    if java -jar "$JAR_FILE" dbAnalytics migrate config.yml; then
        log_success "ClickHouse migrations completed successfully"
    else
        # TODO: dbAnalytics clear-checksums not supported by liquibase-clickhouse yet,
        #  this would enable automatic recovery
        log_error "ClickHouse migrations failed"
        print_migrations_recovery_message
        exit 1
    fi

    log_success "All database migrations completed successfully"
}

wait_for_backend_ready() {
    require_command curl
    log_info "Waiting for backend to be ready on port ${BACKEND_PORT}..."
    local max_wait=60
    local count=0
    local backend_ready=false

    while [ $count -lt $max_wait ]; do
        if curl -sf "http://localhost:${BACKEND_PORT}/health-check" >/dev/null 2>&1; then
            backend_ready=true
            break
        fi
        sleep 1
        count=$((count + 1))

        if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
            log_error "Backend process died while waiting for it to be ready"
            log_error "Check logs: tail -f $BACKEND_LOG_FILE"
            rm -f "$BACKEND_PID_FILE"
            return 1
        fi
    done

    if [ "$backend_ready" = true ]; then
        log_success "Backend is ready and accepting connections"
        log_info "Backend API: ${GREEN}http://localhost:${BACKEND_PORT}${NC}"
        if [ "$DEBUG_MODE" = "true" ]; then
            log_debug "Debug mode enabled - check logs for detailed output"
        fi
        return 0
    else
        log_error "Backend failed to become ready after ${max_wait}s"
        log_error "Check logs: tail -f $BACKEND_LOG_FILE"
        return 1
    fi
}

# Function to start backend
start_backend() {
    require_command java
    log_info "Starting backend on port ${BACKEND_PORT}..."
    log_debug "Backend directory: $BACKEND_DIR"
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    # Check if backend is already running
    if [ -f "$BACKEND_PID_FILE" ]; then
        BACKEND_PID=$(cat "$BACKEND_PID_FILE")
        if kill -0 "$BACKEND_PID" 2>/dev/null; then
            log_warning "Backend is already running (PID: $BACKEND_PID)"
            return 0
        else
            log_warning "Removing stale backend PID file (process $BACKEND_PID no longer exists)"
            rm -f "$BACKEND_PID_FILE"
        fi
    fi

    # Set environment variables
    export CORS=true

    # Set worktree-specific ports for backend to connect to infrastructure
    export SERVER_APPLICATION_PORT="$BACKEND_PORT"
    export SERVER_ADMIN_PORT="$BACKEND_ADMIN_PORT"
    export STATE_DB_URL="localhost:${MYSQL_PORT}/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true"
    export REDIS_URL="${REDIS_URL:-redis://:opik@localhost:${REDIS_PORT}/0}"
    export ANALYTICS_DB_PORT="${CLICKHOUSE_HTTP_PORT}"

    log_debug "Backend configured with:"
    log_debug "  SERVER_APPLICATION_PORT=$SERVER_APPLICATION_PORT"
    log_debug "  SERVER_ADMIN_PORT=$SERVER_ADMIN_PORT"
    log_debug "  STATE_DB_URL=$STATE_DB_URL"
    log_debug "  REDIS_URL=$REDIS_URL"
    log_debug "  ANALYTICS_DB_PORT=$ANALYTICS_DB_PORT"

    # Set debug logging if debug mode is enabled
    if [ "$DEBUG_MODE" = "true" ]; then
        export GENERAL_LOG_LEVEL="DEBUG"
        export OPIK_LOG_LEVEL="DEBUG"
        log_debug "Debug logging enabled - GENERAL_LOG_LEVEL=DEBUG, OPIK_LOG_LEVEL=DEBUG"
    fi

    # Find and validate the JAR file
    if ! find_jar_files; then
        log_warning "No backend JAR file found in target/. Building backend automatically..."
        build_backend

        # Re-scan for JAR files after build
        if ! find_jar_files; then
            log_error "Backend build completed but no JAR file found. Build may have failed."
            exit 1
        fi
    fi

    log_debug "Starting backend with JAR: $JAR_FILE"
    log_debug "Command: java -jar $JAR_FILE server config.yml"

    # Start backend in background using the JAR file
    nohup java -jar "$JAR_FILE" \
        server config.yml \
        > "$BACKEND_LOG_FILE" 2>&1 &

    BACKEND_PID=$!
    echo "$BACKEND_PID" > "$BACKEND_PID_FILE"

    log_debug "Backend process started with PID: $BACKEND_PID"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$BACKEND_PID" 2>/dev/null; then
        log_success "Backend process started (PID: $BACKEND_PID)"
        log_info "Backend logs: tail -f $BACKEND_LOG_FILE"

        if ! wait_for_backend_ready; then
            exit 1
        fi
    else
        log_error "Backend failed to start. Check logs: cat $BACKEND_LOG_FILE"
        rm -f "$BACKEND_PID_FILE"
        exit 1
    fi
}

# Function to start frontend
start_frontend() {
    require_command npm
    log_info "Starting frontend on port ${FRONTEND_PORT}..."
    log_debug "Frontend directory: $FRONTEND_DIR"
    cd "$FRONTEND_DIR" || { log_error "Frontend directory not found"; exit 1; }

    # Check if frontend is already running
    if [ -f "$FRONTEND_PID_FILE" ]; then
        FRONTEND_PID=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$FRONTEND_PID" 2>/dev/null; then
            log_warning "Frontend is already running (PID: $FRONTEND_PID)"
            return 0
        else
            log_warning "Removing stale frontend PID file (process $FRONTEND_PID no longer exists)"
            rm -f "$FRONTEND_PID_FILE"
        fi
    fi

    # Set debug logging for frontend if debug mode is enabled
    if [ "$DEBUG_MODE" = "true" ]; then
        export NODE_ENV="development"
        log_debug "Frontend debug mode enabled - NODE_ENV=development"
    fi

    # Set worktree-specific ports for frontend
    export VITE_DEV_PORT="$FRONTEND_PORT"
    export VITE_BACKEND_PORT="$BACKEND_PORT"
    export VITE_OPIK_AI_BACKEND_PORT="$OPIK_AI_BACKEND_PORT"

    log_debug "Frontend configured with:"
    log_debug "  VITE_DEV_PORT=$VITE_DEV_PORT"
    log_debug "  VITE_BACKEND_PORT=$VITE_BACKEND_PORT"
    log_debug "  VITE_OPIK_AI_BACKEND_PORT=$VITE_OPIK_AI_BACKEND_PORT"

    # Configure frontend API base URL (defaults to /api in frontend code if not set)
    # The Vite dev server proxy will forward /api/* requests to the backend
    if [ -z "${VITE_BASE_API_URL:-}" ]; then
        log_debug "Frontend API base URL (VITE_BASE_API_URL) not set, will use default from frontend code: /api"
    else
        log_info "Frontend API base URL (VITE_BASE_API_URL) set to: $VITE_BASE_API_URL"
    fi

    log_debug "Starting frontend with: npm run start"

    # Start frontend in background with interactive mode disabled
    CI=true nohup npm run start > "$FRONTEND_LOG_FILE" 2>&1 &
    FRONTEND_PID=$!
    echo "$FRONTEND_PID" > "$FRONTEND_PID_FILE"

    log_debug "Frontend process started with PID: $FRONTEND_PID"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$FRONTEND_PID" 2>/dev/null; then
        log_success "Frontend started successfully (PID: $FRONTEND_PID)"
        log_info "Frontend available at: ${GREEN}http://localhost:${FRONTEND_PORT}${NC}"
        log_info "Frontend logs: tail -f $FRONTEND_LOG_FILE"
    else
        log_error "Frontend failed to start. Check logs: cat $FRONTEND_LOG_FILE"
        rm -f "$FRONTEND_PID_FILE"
        exit 1
    fi
}

# Function to stop backend
stop_backend() {
    if [ -f "$BACKEND_PID_FILE" ]; then
        BACKEND_PID=$(cat "$BACKEND_PID_FILE")
        if kill -0 "$BACKEND_PID" 2>/dev/null; then
            log_info "Stopping backend (PID: $BACKEND_PID)..."
            kill "$BACKEND_PID"

            # Wait for graceful shutdown
            for _ in {1..10}; do
                if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running
            if kill -0 "$BACKEND_PID" 2>/dev/null; then
                log_warning "Force killing backend..."
                kill -9 "$BACKEND_PID"
            fi

            log_success "Backend stopped"
        else
            log_warning "Backend PID file exists but process is not running (cleaning up stale PID file)"
        fi
        rm -f "$BACKEND_PID_FILE"
    else
        log_warning "Backend is not running"
    fi
}

# Function to stop frontend
stop_frontend() {
    if [ -f "$FRONTEND_PID_FILE" ]; then
        FRONTEND_PID=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$FRONTEND_PID" 2>/dev/null; then
            log_info "Stopping frontend (PID: $FRONTEND_PID)..."

            # First try to kill just the main process
            kill -TERM "$FRONTEND_PID" 2>/dev/null

            # Wait for graceful shutdown
            for _ in {1..10}; do
                if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running (kill main process and find children)
            if kill -0 "$FRONTEND_PID" 2>/dev/null; then
                log_warning "Force killing frontend..."
                kill -9 "$FRONTEND_PID" 2>/dev/null

                # Also kill any child processes that may still be running
                CHILD_PIDS=$(pgrep -P "$FRONTEND_PID" 2>/dev/null || true)
                if [ -n "$CHILD_PIDS" ]; then
                    log_warning "Killing remaining child processes (PIDs: $CHILD_PIDS)..."
                    for PID in $CHILD_PIDS; do
                        kill -9 "$PID" 2>/dev/null || true
                    done
                fi
            fi

            log_success "Frontend stopped"
        else
            log_warning "Frontend PID file exists but process is not running (cleaning up stale PID file)"
        fi
        rm -f "$FRONTEND_PID_FILE"
    else
        log_warning "Frontend is not running"
    fi

    # Clean up any orphaned processes by looking for processes with our frontend directory path
    # This is safe and compatible across Unix systems
    ORPHANED_PIDS=$(pgrep -f "$FRONTEND_DIR" 2>/dev/null || true)
    
    if [ -n "$ORPHANED_PIDS" ]; then
        for PID in $ORPHANED_PIDS; do
            if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
                # Get process info to verify it's actually our frontend process
                PROCESS_INFO=$(ps -p "$PID" -o comm,args --no-headers 2>/dev/null || true)
                
                # Only kill if it's an npm/node/vite process AND contains our directory path
                if [[ "$PROCESS_INFO" =~ (npm|node|vite) ]] && [[ "$PROCESS_INFO" =~ $FRONTEND_DIR ]]; then
                    log_warning "Cleaning up orphaned process: PID $PID - $PROCESS_INFO"
                    kill -TERM "$PID" 2>/dev/null || true
                    sleep 1
                    # Force kill if still running
                    if kill -0 "$PID" 2>/dev/null; then
                        kill -9 "$PID" 2>/dev/null || true
                    fi
                fi
            fi
        done
    fi
}

# Function to wait for AI backend to be ready
wait_for_ai_backend_ready() {
    require_command curl
    log_info "Waiting for AI backend to be ready on port ${OPIK_AI_BACKEND_PORT}..."
    local max_wait=60
    local count=0
    local ai_backend_ready=false

    while [ $count -lt $max_wait ]; do
        if curl -sf "http://localhost:${OPIK_AI_BACKEND_PORT}/opik-ai/healthz" >/dev/null 2>&1; then
            ai_backend_ready=true
            break
        fi
        sleep 1
        count=$((count + 1))

        if ! kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
            log_error "AI backend process died while waiting for it to be ready"
            log_error "Check logs: tail -f $AI_BACKEND_LOG_FILE"
            rm -f "$AI_BACKEND_PID_FILE"
            return 1
        fi
    done

    if [ "$ai_backend_ready" = true ]; then
        log_success "AI backend is ready and accepting connections"
        log_info "AI backend API: ${GREEN}http://localhost:${OPIK_AI_BACKEND_PORT}${NC}"
        if [ "$DEBUG_MODE" = "true" ]; then
            log_debug "Debug mode enabled - check logs for detailed output"
        fi
        return 0
    else
        log_error "AI backend failed to become ready after ${max_wait}s"
        log_error "Check logs: tail -f $AI_BACKEND_LOG_FILE"
        return 1
    fi
}

# Function to start AI backend
start_ai_backend() {
    require_command uv
    log_info "Starting AI backend on port ${OPIK_AI_BACKEND_PORT}..."
    log_debug "AI backend directory: $AI_BACKEND_DIR"
    
    # Verify AI backend directory exists
    if [ ! -d "$AI_BACKEND_DIR" ]; then
        log_error "AI backend directory not found: $AI_BACKEND_DIR"
        exit 1
    fi

    # Check if AI backend is already running
    if [ -f "$AI_BACKEND_PID_FILE" ]; then
        AI_BACKEND_PID=$(cat "$AI_BACKEND_PID_FILE")
        if kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
            log_warning "AI backend is already running (PID: $AI_BACKEND_PID)"
            return 0
        else
            log_warning "Removing stale AI backend PID file (process $AI_BACKEND_PID no longer exists)"
            rm -f "$AI_BACKEND_PID_FILE"
        fi
    fi

    # Check if uv sync has been run
    if [ ! -d ".venv" ]; then
        log_warning "Virtual environment not found. Building AI backend automatically..."
        build_ai_backend
    fi

    # Set environment variables for local development
    export PORT="${OPIK_AI_BACKEND_PORT}"
    export URL_PREFIX="/opik-ai"
    export DEVELOPMENT_MODE="true"
    export DEV_USER_ID="local-dev-user"
    export DEV_WORKSPACE_NAME="default"
    # Use 127.0.0.1 instead of localhost to force TCP connection (localhost uses Unix socket on Linux)
    export SESSION_SERVICE_URI="mysql://opik:opik@127.0.0.1:${MYSQL_PORT}/opik"
    export AGENT_OPIK_URL="http://localhost:${BACKEND_PORT}"
    export OPIK_URL_OVERRIDE="http://localhost:${BACKEND_PORT}"
    # Set PYTHONPATH to include the src directory where opik_ai_backend module is located
    export PYTHONPATH="${AI_BACKEND_DIR}/src:${PYTHONPATH:-}"

    log_debug "AI backend configured with:"
    log_debug "  PORT=$PORT"
    log_debug "  URL_PREFIX=$URL_PREFIX"
    log_debug "  DEVELOPMENT_MODE=$DEVELOPMENT_MODE"
    log_debug "  SESSION_SERVICE_URI=$SESSION_SERVICE_URI"
    log_debug "  AGENT_OPIK_URL=$AGENT_OPIK_URL"
    log_debug "  OPIK_URL_OVERRIDE=$OPIK_URL_OVERRIDE"
    log_debug "  PYTHONPATH=$PYTHONPATH"

    # Set debug logging if debug mode is enabled
    if [ "$DEBUG_MODE" = "true" ]; then
        log_debug "Debug logging enabled for AI backend"
    fi

    log_debug "Starting AI backend with: uv run --directory $AI_BACKEND_DIR uvicorn opik_ai_backend.main:app --host 0.0.0.0 --port ${OPIK_AI_BACKEND_PORT} --reload --reload-dir src"

    # Start AI backend in background with --reload for hot-reloading during development
    # --reload-dir src: Watch the src directory for changes
    # PYTHONPATH includes src/ so the opik_ai_backend module can be imported
    PYTHONPATH="${AI_BACKEND_DIR}/src:${PYTHONPATH:-}" nohup uv run --directory "$AI_BACKEND_DIR" uvicorn opik_ai_backend.main:app --host 0.0.0.0 --port "${OPIK_AI_BACKEND_PORT}" --reload --reload-dir src \
        > "$AI_BACKEND_LOG_FILE" 2>&1 &

    AI_BACKEND_PID=$!
    echo "$AI_BACKEND_PID" > "$AI_BACKEND_PID_FILE"

    log_debug "AI backend process started with PID: $AI_BACKEND_PID"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
        log_success "AI backend process started (PID: $AI_BACKEND_PID)"
        log_info "AI backend logs: tail -f $AI_BACKEND_LOG_FILE"

        if ! wait_for_ai_backend_ready; then
            exit 1
        fi
    else
        log_error "AI backend failed to start. Check logs: cat $AI_BACKEND_LOG_FILE"
        rm -f "$AI_BACKEND_PID_FILE"
        exit 1
    fi
}

# Function to stop AI backend
stop_ai_backend() {
    if [ -f "$AI_BACKEND_PID_FILE" ]; then
        AI_BACKEND_PID=$(cat "$AI_BACKEND_PID_FILE")
        if kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
            log_info "Stopping AI backend (PID: $AI_BACKEND_PID)..."
            kill "$AI_BACKEND_PID"

            # Wait for graceful shutdown
            for _ in {1..10}; do
                if ! kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
                    break
                fi
                sleep 1
            done
            
            # Force kill if still running
            if kill -0 "$AI_BACKEND_PID" 2>/dev/null; then
                log_warning "Force killing AI backend..."
                kill -9 "$AI_BACKEND_PID"
            fi

            log_success "AI backend stopped"
        else
            log_warning "AI backend PID file exists but process is not running (cleaning up stale PID file)"
        fi
        rm -f "$AI_BACKEND_PID_FILE"
    else
        log_warning "AI backend is not running"
    fi
}

# Helper function to display backend process status
# Returns: 0 if running, 1 if stopped
display_backend_process_status() {
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "Backend: ${GREEN}RUNNING${NC} (PID: $(cat "$BACKEND_PID_FILE"))"
        return 0
    else
        echo -e "Backend: ${RED}STOPPED${NC}"
        return 1
    fi
}

# Helper function to display access information
# Args: $1 = UI URL (e.g., "http://localhost:5174" or "http://localhost:5173")
#       $2 = show manual edit warning (true/false)
show_access_information() {
    local ui_url="$1"
    local show_manual_edit="${2:-true}"

    echo ""
    echo -e "${GREEN}🚀 Opik Development Environment is Ready!${NC}"
    echo -e "${BLUE}📊  Access the UI:     ${ui_url}${NC}"
    echo -e "${BLUE}🛠️  API ping Endpoint: http://localhost:${BACKEND_PORT}/is-alive/ping${NC}"
    echo ""
    echo -e "${BLUE}ℹ️  SDK Configuration Required:${NC}"
    echo -e "To use the Opik SDK with your local development environment, you MUST configure it to point to your local instance."
    echo ""
    echo -e "${BLUE}Run SDK Configuration Command:${NC}"
    echo "  opik configure --use_local"

    if [ "$show_manual_edit" = true ]; then
        echo "  # When prompted:"
        echo "  #   - Choose 'Local deployment' option"
        echo "  #   - Enter URL: http://localhost:${BACKEND_PORT}"
        echo ""
        echo -e "${YELLOW}⚠️  IMPORTANT: Manual Configuration File Edit Required!${NC}"
        echo -e "After running 'opik configure', you MUST manually edit the configuration file to remove '/api' from the URL."
        echo ""
        echo -e "${BLUE}Edit the configuration file:${NC}"
        echo "  # Open the configuration file, by default: ~/.opik.config"
        echo ""
        echo "  # Change this line:"
        echo "  url_override = http://localhost:${BACKEND_PORT}/api/"
        echo ""
        echo "  # To this (remove '/api'):"
        echo "  url_override = http://localhost:${BACKEND_PORT}"
    else
        echo "  # When prompted, use URL: ${ui_url}"
    fi

    echo ""
    echo -e "${BLUE}Alternative - Environment Variables:${NC}"
    # When no manual edit is required (BE-only mode), append /api to the URL
    if [ "$show_manual_edit" = true ]; then
        echo "  export OPIK_URL_OVERRIDE='http://localhost:${BACKEND_PORT}'"
    else
        echo "  export OPIK_URL_OVERRIDE='${ui_url}/api'"
    fi
    echo "  export OPIK_WORKSPACE='default'"
    echo ""
    echo -e "${YELLOW}Important Notes:${NC}"

    echo "  • The configuration file is located at ~/.opik.config by default"

    if [ "$show_manual_edit" = true ]; then
        echo "  • You MUST remove '/api' from the URL for local development"
    fi

    echo "  • Default workspace is 'default'"
    echo "  • No API key required for local instances"
    echo ""
    echo -e "${BLUE}📖 For complete configuration documentation, visit:${NC}"
    echo -e "   https://www.comet.com/docs/opik/tracing/sdk_configuration"
}

create_demo_data() {
    local mode="$1"
    
    log_info "Creating demo data..."
    cd "$PROJECT_ROOT" || { log_error "Project root directory not found"; return 1; }

    if ./opik.sh "$mode" --demo-data; then
        log_success "Demo data created"
        return 0
    else
        log_warning "Demo data creation failed, but services are running"
        return 1
    fi
}

# Function to verify services
verify_services() {
    log_info "=== Opik Development Status (Worktree: ${WORKTREE_ID}, Offset: ${PORT_OFFSET}) ==="

    local docker_services_running=false
    if verify_local_be_fe; then
        echo -e "Docker Services: ${GREEN}RUNNING${NC}"
        docker_services_running=true
    else
        echo -e "Docker Services: ${RED}STOPPED${NC}"
    fi

    # Backend process status
    local backend_running=false
    if display_backend_process_status; then
        backend_running=true
    fi

    # Frontend process status
    local frontend_running=false
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "Frontend Process: ${GREEN}RUNNING${NC} (PID: $(cat "$FRONTEND_PID_FILE"))"
        frontend_running=true
    else
        echo -e "Frontend Process: ${RED}STOPPED${NC}"
    fi

    # Show access information if all services are running
    if [ "$docker_services_running" = true ] && [ "$backend_running" = true ] && [ "$frontend_running" = true ]; then
        show_access_information "http://localhost:${FRONTEND_PORT}" true
    fi

    echo ""
    echo "Logs:"
    echo "  Backend Process:  tail -f $BACKEND_LOG_FILE"
    echo "  Frontend Process: tail -f $FRONTEND_LOG_FILE"
}

# Function to verify BE-only services
verify_be_only_services() {
    log_info "=== Opik BE-Only Development Status (Worktree: ${WORKTREE_ID}, Offset: ${PORT_OFFSET}) ==="

    local docker_services_running=false
    if verify_local_be; then
        echo -e "Docker Services: ${GREEN}RUNNING${NC}"
        docker_services_running=true
    else
        echo -e "Docker Services: ${RED}STOPPED${NC}"
    fi

    # Backend process status
    local backend_running=false
    if display_backend_process_status; then
        backend_running=true
    fi

    # Show access information if all services are running
    if [ "$docker_services_running" = true ] && [ "$backend_running" = true ]; then
        # In BE-only mode, frontend runs in Docker on standard port 5173
        show_access_information "http://localhost:5173" false
    fi

    echo ""
    echo "Logs:"
    echo "  Backend Process:  tail -f $BACKEND_LOG_FILE"
    echo "  Frontend:         docker logs -f ${RESOURCE_PREFIX}-frontend-1"
}

# Function to verify AI backend services
verify_ai_backend_services() {
    log_info "=== Opik AI Backend Development Status (Worktree: ${WORKTREE_ID}, Offset: ${PORT_OFFSET}) ==="

    local docker_services_running=false
    if verify_local_ai; then
        echo -e "Docker Services: ${GREEN}RUNNING${NC}"
        docker_services_running=true
    else
        echo -e "Docker Services: ${RED}STOPPED${NC}"
    fi

    # AI backend process status
    local ai_backend_running=false
    if [ -f "$AI_BACKEND_PID_FILE" ] && kill -0 "$(cat "$AI_BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "AI Backend Process: ${GREEN}RUNNING${NC} (PID: $(cat "$AI_BACKEND_PID_FILE"))"
        ai_backend_running=true
    else
        echo -e "AI Backend Process: ${RED}STOPPED${NC}"
    fi

    # Frontend process status
    local frontend_running=false
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "Frontend Process: ${GREEN}RUNNING${NC} (PID: $(cat "$FRONTEND_PID_FILE"))"
        frontend_running=true
    else
        echo -e "Frontend Process: ${RED}STOPPED${NC}"
    fi

    # Show access information if all services are running
    if [ "$docker_services_running" = true ] && [ "$ai_backend_running" = true ] && [ "$frontend_running" = true ]; then
        show_access_information "http://localhost:${FRONTEND_PORT}" true
    fi

    echo ""
    echo "Logs:"
    echo "  AI Backend Process: tail -f $AI_BACKEND_LOG_FILE"
    echo "  Frontend Process:   tail -f $FRONTEND_LOG_FILE"
    echo "  Backend:            docker logs -f ${RESOURCE_PREFIX}-backend-1"
}

# Function to start services (without building)
start_services() {
    log_info "=== Starting Opik Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_warning "=== Not rebuilding: the latest local changes may not be reflected ==="

    # Check for port collisions before starting
    if ! check_port_collisions; then
        exit 1
    fi

    log_info "Step 1/5: Starting Docker services..."
    start_local_be_fe
    log_info "Step 2/5: Running DB migrations..."
    run_db_migrations
    log_info "Step 3/5: Starting backend process..."
    start_backend
    log_info "Step 4/5: Starting frontend process..."
    start_frontend
    log_info "Step 5/5: Creating demo data..."
    create_demo_data "--local-be-fe"
    log_success "=== Start Complete ==="
    verify_services
}

# Function to stop services
stop_services() {
    log_info "=== Stopping Opik Development Environment ==="
    log_info "Step 1/3: Stopping frontend..."
    stop_frontend
    log_info "Step 2/3: Stopping backend..."
    stop_backend
    log_info "Step 3/3: Stopping Docker services..."
    stop_local_be_fe
    log_success "=== Stop Complete ==="
}

# Function to run migrations
migrate_services() {
    log_info "=== Running Database Migrations ==="
    log_info "Step 1/3: Starting Docker services..."
    start_local_be_fe
    log_info "Step 2/3: Building backend..."
    build_backend
    log_info "Step 3/3: Running DB migrations..."
    run_db_migrations
    log_success "=== Migrations Complete ==="
}

# Function to restart services (stop, build, start)
restart_services() {
    log_info "=== Restarting Opik Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_info "Step 1/10: Stopping frontend process..."
    stop_frontend
    log_info "Step 2/10: Stopping backend process..."
    stop_backend
    log_info "Step 3/10: Stopping Docker services..."
    stop_local_be_fe
    log_info "Step 4/10: Starting Docker services..."
    start_local_be_fe
    log_info "Step 5/10: Building backend..."
    build_backend
    log_info "Step 6/10: Building frontend..."
    build_frontend
    log_info "Step 7/10: Running DB migrations..."
    run_db_migrations
    log_info "Step 8/10: Starting backend process..."
    start_backend
    log_info "Step 9/10: Starting frontend process..."
    start_frontend
    log_info "Step 10/10: Creating demo data..."
    create_demo_data "--local-be-fe"
    log_success "=== Restart Complete ==="
    verify_services
}

# Function for quick restart (only rebuild backend, keep infrastructure running)
quick_restart_services() {
    log_info "=== Quick Restart (Backend Only) ==="
    
    # Check if infrastructure is running, start it if not
    log_info "Step 1/7: Checking Docker infrastructure..."
    if verify_local_be_fe; then
        log_success "Docker infrastructure is already running"
    else
        log_warning "Docker infrastructure is not running, starting it..."
        start_local_be_fe
        log_info "Running DB migrations..."
        run_db_migrations
    fi
    
    log_info "Step 2/7: Stopping frontend..."
    stop_frontend
    log_info "Step 3/7: Stopping backend..."
    stop_backend
    log_info "Step 4/7: Building backend..."
    build_backend
    log_info "Step 5/7: Starting backend..."
    start_backend
    
    # Check if package.json has changed since last npm install
    log_info "Step 6/7: Checking frontend dependencies..."
    local package_json="$FRONTEND_DIR/package.json"
    local package_lock="$FRONTEND_DIR/package-lock.json"
    local node_modules="$FRONTEND_DIR/node_modules"
    
    local needs_install=false
    
    if [ ! -d "$node_modules" ]; then
        log_info "node_modules not found, will install dependencies"
        needs_install=true
    elif [ ! -f "$package_lock" ]; then
        log_info "package-lock.json not found, will install dependencies"
        needs_install=true
    elif [ "$package_json" -nt "$package_lock" ]; then
        log_info "package.json is newer than package-lock.json, will install dependencies"
        needs_install=true
    else
        log_info "Frontend dependencies are up to date, skipping npm install"
    fi
    
    if [ "$needs_install" = true ]; then
        build_frontend
    fi
    
    log_info "Step 7/7: Starting frontend..."
    start_frontend
    log_success "=== Quick Restart Complete ==="
    verify_services
}

# Function to start BE-only services (without building)
start_be_only_services() {
    log_info "=== Starting Opik BE-Only Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_warning "=== Not rebuilding: the latest local changes may not be reflected ==="

    # Check for port collisions before starting
    if ! check_port_collisions; then
        exit 1
    fi

    log_info "Step 1/4: Starting Docker services..."
    start_local_be
    log_info "Step 2/4: Running DB migrations..."
    run_db_migrations
    log_info "Step 3/4: Starting backend process..."
    start_backend
    log_info "Step 4/4: Creating demo data..."
    create_demo_data "--local-be"
    log_success "=== BE-Only Start Complete ==="
    verify_be_only_services
}

# Function to stop BE-only services
stop_be_only_services() {
    log_info "=== Stopping Opik BE-Only Development Environment ==="
    log_info "Step 1/2: Stopping backend process..."
    stop_backend
    log_info "Step 2/2: Stopping Docker services..."
    stop_local_be
    log_success "=== BE-Only Stop Complete ==="
}

# Function to restart BE-only services (stop, build, start)
restart_be_only_services() {
    log_info "=== Restarting Opik BE-Only Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_info "Step 1/7: Stopping backend process..."
    stop_backend
    log_info "Step 2/7: Stopping Docker services..."
    stop_local_be
    log_info "Step 3/7: Starting Docker services..."
    start_local_be
    log_info "Step 4/7: Building backend..."
    build_backend
    log_info "Step 5/7: Running DB migrations..."
    run_db_migrations
    log_info "Step 6/7: Starting backend process..."
    start_backend
    log_info "Step 7/7: Creating demo data..."
    create_demo_data "--local-be"
    log_success "=== BE-Only Restart Complete ==="
    verify_be_only_services
}

# Function to start AI backend services (without building)
start_ai_backend_services() {
    log_info "=== Starting Opik AI Backend Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_warning "=== Not rebuilding: the latest local changes may not be reflected ==="

    # Check for port collisions before starting
    if ! check_port_collisions; then
        exit 1
    fi

    # Enable OpikAI feature toggle for the backend
    export TOGGLE_OPIK_AI_ENABLED="true"

    log_info "Step 1/5: Starting Docker services..."
    start_local_ai
    log_info "Step 2/5: Running DB migrations..."
    run_db_migrations
    log_info "Step 3/5: Starting AI backend process..."
    start_ai_backend
    log_info "Step 4/5: Starting frontend process..."
    start_frontend
    log_info "Step 5/5: Creating demo data..."
    create_demo_data "--local-ai"
    log_success "=== AI Backend Start Complete ==="
    verify_ai_backend_services
}

# Function to stop AI backend services
stop_ai_backend_services() {
    log_info "=== Stopping Opik AI Backend Development Environment ==="
    log_info "Step 1/3: Stopping frontend..."
    stop_frontend
    log_info "Step 2/3: Stopping AI backend..."
    stop_ai_backend
    log_info "Step 3/3: Stopping Docker services..."
    stop_local_ai
    log_success "=== AI Backend Stop Complete ==="
}

# Function to restart AI backend services (stop, build, start)
restart_ai_backend_services() {
    log_info "=== Restarting Opik AI Backend Development Environment (Worktree: ${WORKTREE_ID}) ==="
    log_info "Step 1/9: Stopping frontend process..."
    stop_frontend
    log_info "Step 2/9: Stopping AI backend process..."
    stop_ai_backend
    log_info "Step 3/9: Stopping Docker services..."
    stop_local_ai

    # Enable OpikAI feature toggle for the backend
    export TOGGLE_OPIK_AI_ENABLED="true"

    log_info "Step 4/9: Starting Docker services..."
    start_local_ai
    log_info "Step 5/9: Running DB migrations..."
    run_db_migrations
    log_info "Step 6/9: Building AI backend..."
    build_ai_backend
    log_info "Step 7/9: Building frontend..."
    build_frontend
    log_info "Step 8/9: Starting AI backend process..."
    start_ai_backend
    log_info "Step 9/9: Starting frontend process..."
    start_frontend
    log_success "=== AI Backend Restart Complete ==="
    verify_ai_backend_services
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Standard Mode (BE and FE services as processes):"
    echo "  --start         - Start Docker infrastructure, and BE and FE processes (without building)"
    echo "  --stop          - Stop Docker infrastructure, and BE and FE processes"
    echo "  --restart       - Stop, build, and start Docker infrastructure, and BE and FE processes (DEFAULT IF NO OPTIONS PROVIDED)"
    echo "  --quick-restart - Quick restart: stop BE/FE, rebuild BE only, start BE/FE (keeps infrastructure running)"
    echo "  --verify        - Verify status of Docker infrastructure, and BE and FE processes"
    echo ""
    echo "BE-Only Mode (BE as process, FE in Docker):"
    echo "  --be-only-start    - Start Docker infrastructure and FE, and backend process (without building)"
    echo "  --be-only-stop     - Stop Docker infrastructure and FE, and backend process"
    echo "  --be-only-restart  - Stop, build, and start Docker infrastructure and FE, and backend process"
    echo "  --be-only-verify   - Verify status of Docker infrastructure and FE, and backend process"
    echo ""
    echo "Other options:"
    echo "  --build-be       - Build backend"
    echo "  --build-fe       - Build frontend"
    echo "  --migrate        - Run database migrations"
    echo "  --lint-be        - Lint backend code"
    echo "  --lint-fe        - Lint frontend code"
    echo "  --debug          - Enable debug mode (meant to be combined with other flags)"
    echo "  --logs           - Show logs for backend and frontend services"
    echo "  --help           - Show this help message"
    echo ""
    echo "Multi-Worktree Support:"
    echo "  This script supports running multiple Opik instances from different git worktrees."
    echo "  Each worktree gets unique ports based on a hash of its path."
    echo ""
    echo "Environment Variables:"
    echo "  DEBUG_MODE=true       - Enable debug mode"
    echo "  OPIK_PORT_OFFSET=<n>  - Override automatic port offset (0-99)"
}

# Function to handle unknown options
handle_unknown_option() {
    local option="$1"
    log_error "Unknown option: $option"
    echo ""
    show_usage
    exit 1
}

# Function to show logs
show_logs() {
    log_info "=== Recent Logs (Worktree: ${WORKTREE_ID}) ==="

    if [ -f "$BACKEND_LOG_FILE" ]; then
        echo -e "\n${BLUE}Backend logs (last 20 lines):${NC}"
        tail -20 "$BACKEND_LOG_FILE"
    fi

    if [ -f "$FRONTEND_LOG_FILE" ]; then
        echo -e "\n${BLUE}Frontend logs (last 20 lines):${NC}"
        tail -20 "$FRONTEND_LOG_FILE"
    fi

    echo -e "\n${BLUE}To follow logs in real-time:${NC}"
    echo "  Backend:  tail -f $BACKEND_LOG_FILE"
    echo "  Frontend: tail -f $FRONTEND_LOG_FILE"
}

# Function to check if a port is in use
is_port_in_use() {
    local port="$1"
    if command -v lsof &>/dev/null; then
        lsof -iTCP:"$port" -sTCP:LISTEN -t &>/dev/null
    elif command -v netstat &>/dev/null; then
        netstat -tuln 2>/dev/null | grep -q ":$port "
    elif command -v ss &>/dev/null; then
        ss -tuln 2>/dev/null | grep -q ":$port "
    else
        # Can't check, assume not in use
        return 1
    fi
}

# Function to detect port collisions before starting services
check_port_collisions() {
    local has_collision=false
    local ports_to_check=(
        "$BACKEND_PORT:Backend"
        "$BACKEND_ADMIN_PORT:Backend Admin"
        "$FRONTEND_PORT:Frontend"
        "$MYSQL_PORT:MySQL"
        "$REDIS_PORT:Redis"
        "$CLICKHOUSE_HTTP_PORT:ClickHouse HTTP"
        "$CLICKHOUSE_NATIVE_PORT:ClickHouse Native"
        "$PYTHON_BACKEND_PORT:Python Backend"
        "$ZOOKEEPER_PORT:Zookeeper"
        "$MINIO_API_PORT:MinIO API"
        "$MINIO_CONSOLE_PORT:MinIO Console"
        "$OPIK_AI_BACKEND_PORT:Opik AI Backend"
    )

    log_info "Checking for port collisions..."

    for port_info in "${ports_to_check[@]}"; do
        local port="${port_info%%:*}"
        local service="${port_info##*:}"

        if is_port_in_use "$port"; then
            log_error "Port $port ($service) is already in use"
            has_collision=true
        fi
    done

    if [ "$has_collision" = true ]; then
        echo ""
        log_error "Port collision detected! Another process is using one or more required ports."
        log_error "This might be caused by:"
        log_error "  - Another Opik instance running from a different worktree"
        log_error "  - Stale containers from a previous run"
        log_error "  - Other services using the same ports"
        echo ""
        log_info "To resolve:"
        log_info "  1. Stop other Opik instances: ./scripts/dev-runner.sh --stop"
        log_info "  2. Use a different port offset: export OPIK_PORT_OFFSET=<0-99>"
        log_info "  3. Check running processes: lsof -i :${BACKEND_PORT}"
        return 1
    fi

    log_success "No port collisions detected"
    return 0
}

# Parse arguments to handle debug flag
ARGS=()
while [[ $# -gt 0 ]]; do
  case $1 in
    --debug)
      DEBUG_MODE=true
      shift # Remove --debug from arguments
      ;;
    *)
      ARGS+=("$1") # Keep other arguments
      shift
      ;;
  esac
done

# Restore arguments without --debug
if [ ${#ARGS[@]} -gt 0 ]; then
  set -- "${ARGS[@]}"
else
  set --  # Clear all arguments
fi

# Show debug mode status
if [ "$DEBUG_MODE" = "true" ]; then
    log_debug "Debug mode is ENABLED"
fi

# Main script logic
case "${1:-}" in
    "--build-be")
        build_backend
        ;;
    "--build-fe")
        build_frontend
        ;;
    "--build-ai")
        build_ai_backend
        ;;
    "--migrate")
        migrate_services
        ;;
    "--start")
        start_services
        ;;
    "--stop")
        stop_services
        ;;
    "--restart")
        restart_services
        ;;
    "--quick-restart")
        quick_restart_services
        ;;
    "--verify")
        verify_services
        ;;
    "--be-only-start")
        start_be_only_services
        ;;
    "--be-only-stop")
        stop_be_only_services
        ;;
    "--be-only-restart")
        restart_be_only_services
        ;;
    "--be-only-verify")
        verify_be_only_services
        ;;
    "--ai-backend-start")
        start_ai_backend_services
        ;;
    "--ai-backend-stop")
        stop_ai_backend_services
        ;;
    "--ai-backend-restart")
        restart_ai_backend_services
        ;;
    "--ai-backend-verify")
        verify_ai_backend_services
        ;;
    "--logs")
        show_logs
        ;;
    "--lint-fe")
        lint_frontend
        ;;
    "--lint-be")
        lint_backend
        ;;
    "--help")
        show_usage
        ;;
    "")
        # Default action: restart
        restart_services
        ;;
    *)
        handle_unknown_option "$1"
        ;;
esac
