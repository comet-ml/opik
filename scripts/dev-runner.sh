#!/bin/bash

set -euo pipefail

# Opik Development Runner Script

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

require_command() {
    if ! command -v "$1" &>/dev/null; then
        log_error "Required command '$1' not found. Please install it."
        exit 1
    fi
}

# Function to build backend
build_backend() {
    require_command mvn
    log_info "Building backend (skipping tests)..."
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    if mvn clean install -DskipTests; then
        log_success "Backend build completed successfully"
    else
        log_error "Backend build failed"
        exit 1
    fi
}

# Function to lint frontend
lint_frontend() {
    require_command npm
    log_info "Linting frontend..."
    cd "$FRONTEND_DIR" || { log_error "Frontend directory not found"; exit 1; }

    if npm run lint; then
        log_success "Frontend linting completed successfully"
    else
        log_error "Frontend linting failed"
        exit 1
    fi
}

# Function to lint backend
lint_backend() {
    require_command mvn
    log_info "Linting backend with spotless..."
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }

    if mvn spotless:apply; then
        log_success "Backend linting completed successfully"
    else
        log_error "Backend linting failed"
        exit 1
    fi
}

# Function to start backend
start_backend() {
    require_command java
    log_info "Starting backend..."
    cd "$BACKEND_DIR" || { log_error "Backend directory not found"; exit 1; }
    
    # Check if backend is already running
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        log_warning "Backend is already running (PID: $(cat "$BACKEND_PID_FILE"))"
        return 0
    fi
    
    # Set environment variables
    export CORS=true

    # Find and validate the JAR file
    # Use portable method to populate array (works with older Bash versions)
    JAR_FILES=()
    while IFS= read -r -d '' jar; do
        JAR_FILES+=("$jar")
    done < <(find target -maxdepth 1 -type f -name 'opik-backend-*.jar' ! -name '*original*' -print0)
    if [ "${#JAR_FILES[@]}" -eq 0 ]; then
        log_error "No backend JAR file found in target/. Please build the backend first."
        exit 1
    elif [ "${#JAR_FILES[@]}" -eq 1 ]; then
        JAR_FILE="${JAR_FILES[0]}"
        log_info "Using JAR file: $JAR_FILE"
    else
        log_warning "Multiple backend JAR files found in target/:"
        for jar in "${JAR_FILES[@]}"; do
            log_warning "  - $jar"
        done
        
        # Sort JAR files by version (assuming semantic versioning in filename)
        # This will work for patterns like opik-backend-1.0-SNAPSHOT.jar, opik-backend-1.1-SNAPSHOT.jar, etc.
        JAR_FILE=$(printf '%s\n' "${JAR_FILES[@]}" | sort -V | tail -n 1)
        log_info "Automatically selected JAR with highest version: $JAR_FILE"
        log_info "To use a different JAR, clean up target/ directory and rebuild"
    fi
    
    # Start backend in background using the JAR file
    nohup java -jar "$JAR_FILE" \
        server config.yml \
        > /tmp/opik-backend.log 2>&1 &

    BACKEND_PID=$!
    echo "$BACKEND_PID" > "$BACKEND_PID_FILE"

    # Wait a bit and check if process is still running
    sleep 3
    if kill -0 "$BACKEND_PID" 2>/dev/null; then
        log_success "Backend started successfully (PID: $BACKEND_PID)"
        log_info "Backend logs: tail -f /tmp/opik-backend.log"
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
    cd "$FRONTEND_DIR" || { log_error "Frontend directory not found"; exit 1; }
    
    # Check if frontend is already running
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        log_warning "Frontend is already running (PID: $(cat "$FRONTEND_PID_FILE"))"
        return 0
    fi
    
    # Start frontend in background
    nohup npm run start > /tmp/opik-frontend.log 2>&1 &
    FRONTEND_PID=$!
    echo "$FRONTEND_PID" > "$FRONTEND_PID_FILE"

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
            log_warning "Backend PID file exists but process is not running"
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
                    log_info "Killing remaining child processes (PIDs: $CHILD_PIDS)..."
                    for PID in $CHILD_PIDS; do
                        kill -9 "$PID" 2>/dev/null || true
                    done
                fi
            fi

            log_success "Frontend stopped"
        else
            log_warning "Frontend PID file exists but process is not running"
        fi
        rm -f "$FRONTEND_PID_FILE"
    else
        log_warning "Frontend is not running"
    fi

    # Clean up any orphaned processes by checking for processes in our project directory
    # This is safer than using broad pkill patterns
    OPIK_FRONTEND_PROCESSES=$(pgrep -f "npm.*start|node.*vite" | xargs)
    if [ -n "$OPIK_FRONTEND_PROCESSES" ]; then
        log_info "Cleaning up remaining frontend processes related to $FRONTEND_DIR..."
        for PID in $OPIK_FRONTEND_PROCESSES; do
            if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
                kill -TERM "$PID" 2>/dev/null || true
                sleep 1
                # Force kill if still running
                if kill -0 "$PID" 2>/dev/null; then
                    kill -9 "$PID" 2>/dev/null || true
                fi
            fi
        done
    fi
}

# Function to show status
show_status() {
    log_info "=== Opik Development Status ==="
    
    # Backend status
    if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
        echo -e "Backend: ${GREEN}RUNNING${NC} (PID: $(cat "$BACKEND_PID_FILE"))"
    else
        echo -e "Backend: ${RED}STOPPED${NC}"
    fi
    
    # Frontend status
    if [ -f "$FRONTEND_PID_FILE" ] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo -e "Frontend: ${GREEN}RUNNING${NC} (PID: $(cat "$FRONTEND_PID_FILE"))"
    else
        echo -e "Frontend: ${RED}STOPPED${NC}"
    fi

    echo ""
    echo "Logs:"
    echo "  Backend:  tail -f /tmp/opik-backend.log"
    echo "  Frontend: tail -f /tmp/opik-frontend.log"
}

# Function to restart services (stop, build, start)
restart_services() {
    log_info "=== Restarting Opik Development Environment ==="
    stop_backend
    stop_frontend
    build_backend
    start_backend
    start_frontend
    show_status
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --build        - Build backend (mvn clean install -DskipTests)"
    echo "  --start        - Start both backend and frontend"
    echo "  --stop         - Stop both backend and frontend"
    echo "  --restart      - Stop, build, and start both services (default)"
    echo "  --status       - Show status of both services"
    echo "  --logs         - Show logs for both services"
    echo "  --lint-fe      - Lint frontend code"
    echo "  --lint-be      - Lint backend code with spotless apply"
    echo "  --help         - Show this help message"
    echo ""
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

# Main script logic
case "${1:-}" in
    "--build")
        build_backend
        ;;
    "--start")
        start_backend
        start_frontend
        show_status
        ;;
    "--stop")
        stop_backend
        stop_frontend
        log_success "All services stopped"
        ;;
    "--restart")
        restart_services
        ;;
    "--status")
        show_status
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
        log_error "Unknown option: $1"
        echo ""
        show_usage
        exit 1
        ;;
esac
