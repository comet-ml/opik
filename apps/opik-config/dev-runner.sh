#!/bin/bash

# Opik Config Demo Runner
# Starts all services needed for the configuration feature demo

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPIK_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Cleanup function
cleanup() {
    log_info "Shutting down services..."
    kill $CONFIG_PID 2>/dev/null || true
    kill $FRONTEND_PID 2>/dev/null || true
    log_success "Services stopped"
}

trap cleanup EXIT

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v uv &> /dev/null; then
        log_error "uv is not installed. Install with: curl -LsSf https://astral.sh/uv/install.sh | sh"
        exit 1
    fi

    if ! command -v npm &> /dev/null; then
        log_error "npm is not installed"
        exit 1
    fi

    log_success "Prerequisites OK"
}

# Start config backend
start_config_backend() {
    log_info "Starting config backend on port 5050..."
    cd "$SCRIPT_DIR"

    # Install dependencies if needed
    if [ ! -d ".venv" ]; then
        log_info "Creating virtual environment..."
        uv venv
    fi

    uv run python -m opik_config &
    CONFIG_PID=$!

    # Wait for service to be ready
    for i in {1..30}; do
        if curl -s http://localhost:5050/health > /dev/null 2>&1; then
            log_success "Config backend ready"
            return 0
        fi
        sleep 0.5
    done

    log_error "Config backend failed to start"
    exit 1
}

# Start frontend
start_frontend() {
    log_info "Starting frontend on port 5173..."
    cd "$OPIK_ROOT/apps/opik-frontend"

    # Install dependencies if needed
    if [ ! -d "node_modules" ]; then
        log_info "Installing frontend dependencies..."
        npm install
    fi

    npm run dev &
    FRONTEND_PID=$!

    # Wait for service to be ready
    for i in {1..60}; do
        if curl -s http://localhost:5173 > /dev/null 2>&1; then
            log_success "Frontend ready"
            return 0
        fi
        sleep 1
    done

    log_warn "Frontend may still be starting..."
}

# Main
main() {
    echo ""
    echo "=========================================="
    echo "  Opik Config Demo Runner"
    echo "=========================================="
    echo ""

    check_prerequisites

    echo ""
    log_info "Starting services..."
    echo ""

    start_config_backend
    start_frontend

    echo ""
    echo "=========================================="
    echo -e "  ${GREEN}All services running!${NC}"
    echo "=========================================="
    echo ""
    echo "  Config Backend: http://localhost:5050"
    echo "  Frontend:       http://localhost:5173"
    echo ""
    echo "  Press Ctrl+C to stop all services"
    echo ""

    # Wait for interrupt
    wait
}

main "$@"
