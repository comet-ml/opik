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
BACKEND_PID_FILE="/tmp/opik-backend.pid"
FRONTEND_PID_FILE="/tmp/opik-frontend.pid"

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
    # Set the database name environment variable for MySQL migrations
    export STATE_DB_DATABASE_NAME="opik"
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
    # Set the connection URL to ensure connection to opik database
    export ANALYTICS_DB_MIGRATIONS_URL="jdbc:clickhouse://localhost:8123"
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
    log_info "Waiting for backend to be ready..."
    local max_wait=60
    local count=0
    local backend_ready=false
    
    while [ $count -lt $max_wait ]; do
        if curl -sf http://localhost:8080/health-check >/dev/null 2>&1; then
            backend_ready=true
            break
        fi
        sleep 1
        count=$((count + 1))
        
        if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
            log_error "Backend process died while waiting for it to be ready"
            log_error "Check logs: tail -f /tmp/opik-backend.log"
            rm -f "$BACKEND_PID_FILE"
            return 1
        fi
    done
    
    if [ "$backend_ready" = true ]; then
        log_success "Backend is ready and accepting connections"
        if [ "$DEBUG_MODE" = "true" ]; then
            log_debug "Debug mode enabled - check logs for detailed output"
        fi
        return 0
    else
        log_error "Backend failed to become ready after ${max_wait}s"
        log_error "Check logs: tail -f /tmp/opik-backend.log"
        return 1
    fi
}

# Function to start backend
start_backend() {
    require_command java
    log_info "Starting backend..."
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
        > /tmp/opik-backend.log 2>&1 &

    BACKEND_PID=$!
    echo "$BACKEND_PID" > "$BACKEND_PID_FILE"

    log_debug "Backend process started with PID: $BACKEND_PID"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$BACKEND_PID" 2>/dev/null; then
        log_success "Backend process started (PID: $BACKEND_PID)"
        log_info "Backend logs: tail -f /tmp/opik-backend.log"
        
        if ! wait_for_backend_ready; then
            exit 1
        fi
    else
        log_error "Backend failed to start. Check logs: cat /tmp/opik-backend.log"
        rm -f "$BACKEND_PID_FILE"
        exit 1
    fi
}

# Function to start frontend
start_frontend() {
    require_command npm
    log_info "Starting frontend..."
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

    # Configure frontend API base URL (defaults to /api in frontend code if not set)
    # The Vite dev server proxy will forward /api/* requests to the backend
    if [ -z "${VITE_BASE_API_URL:-}" ]; then
        log_debug "Frontend API base URL (VITE_BASE_API_URL) not set, will use default from frontend code: /api"
    else
        log_info "Frontend API base URL (VITE_BASE_API_URL) set to: $VITE_BASE_API_URL"
    fi    

    log_debug "Starting frontend with: npm run start"

    # Start frontend in background with interactive mode disabled
    CI=true nohup npm run start > /tmp/opik-frontend.log 2>&1 &
    FRONTEND_PID=$!
    echo "$FRONTEND_PID" > "$FRONTEND_PID_FILE"

    log_debug "Frontend process started with PID: $FRONTEND_PID"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$FRONTEND_PID" 2>/dev/null; then
        log_success "Frontend started successfully (PID: $FRONTEND_PID)"
        log_info "Frontend logs: tail -f /tmp/opik-frontend.log"
    else
        log_error "Frontend failed to start. Check logs: cat /tmp/opik-frontend.log"
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
    echo -e "${BLUE}🛠️  API ping Endpoint: http://localhost:8080/is-alive/ping${NC}"
    echo ""
    echo -e "${BLUE}ℹ️  SDK Configuration Required:${NC}"
    echo -e "To use the Opik SDK with your local development environment, you MUST configure it to point to your local instance."
    echo ""
    echo -e "${BLUE}Run SDK Configuration Command:${NC}"
    echo "  opik configure --use_local"
    
    if [ "$show_manual_edit" = true ]; then
        echo "  # When prompted:"
        echo "  #   - Choose 'Local deployment' option"
        echo "  #   - Enter URL: http://localhost:8080"
        echo ""
        echo -e "${YELLOW}⚠️  IMPORTANT: Manual Configuration File Edit Required!${NC}"
        echo -e "After running 'opik configure', you MUST manually edit the configuration file to remove '/api' from the URL."
        echo ""
        echo -e "${BLUE}Edit the configuration file:${NC}"
        echo "  # Open the configuration file, by default: ~/.opik.config"
        echo ""
        echo "  # Change this line:"
        echo "  url_override = http://localhost:8080/api/"
        echo ""
        echo "  # To this (remove '/api'):"
        echo "  url_override = http://localhost:8080"
    else
        echo "  # When prompted, use URL: ${ui_url}"
    fi
    
    echo ""
    echo -e "${BLUE}Alternative - Environment Variables:${NC}"
    # When no manual edit is required (BE-only mode), append /api to the URL
    if [ "$show_manual_edit" = true ]; then
        echo "  export OPIK_URL_OVERRIDE='http://localhost:8080'"
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
    log_info "=== Opik Development Status ==="
    
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
        show_access_information "http://localhost:5174" true
    fi

    echo ""
    echo "Logs:"
    echo "  Backend Process:  tail -f /tmp/opik-backend.log"
    echo "  Frontend Process: tail -f /tmp/opik-frontend.log"
}

# Function to verify BE-only services
verify_be_only_services() {
    log_info "=== Opik BE-Only Development Status ==="
    
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
        show_access_information "http://localhost:5173" false
    fi

    echo ""
    echo "Logs:"
    echo "  Backend Process:  tail -f /tmp/opik-backend.log"
    echo "  Frontend:         docker logs -f opik-frontend-1"
}

# Function to start services (without building)
start_services() {
    log_info "=== Starting Opik Development Environment ==="
    log_warning "=== Not rebuilding: the latest local changes may not be reflected ==="
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
    log_info "=== Restarting Opik Development Environment ==="
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
    log_info "=== Starting Opik BE-Only Development Environment ==="
    log_warning "=== Not rebuilding: the latest local changes may not be reflected ==="
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
    log_info "=== Restarting Opik BE-Only Development Environment ==="
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
    echo "  --build-be     - Build backend"
    echo "  --build-fe     - Build frontend"
    echo "  --migrate      - Run database migrations"
    echo "  --lint-be      - Lint backend code"
    echo "  --lint-fe      - Lint frontend code"
    echo "  --debug        - Enable debug mode (meant to be combined with other flags)"
    echo "  --logs         - Show logs for backend and frontend services"
    echo "  --help         - Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  DEBUG_MODE=true  - Enable debug mode"
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
    log_info "=== Recent Logs ==="

    if [ -f "/tmp/opik-backend.log" ]; then
        echo -e "\n${BLUE}Backend logs (last 20 lines):${NC}"
        tail -20 /tmp/opik-backend.log
    fi

    if [ -f "/tmp/opik-frontend.log" ]; then
        echo -e "\n${BLUE}Frontend logs (last 20 lines):${NC}"
        tail -20 /tmp/opik-frontend.log
    fi

    echo -e "\n${BLUE}To follow logs in real-time:${NC}"
    echo "  Backend:  tail -f /tmp/opik-backend.log"
    echo "  Frontend: tail -f /tmp/opik-frontend.log"
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
