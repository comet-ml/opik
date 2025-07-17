#!/bin/bash

# Local Development Script for OPIK
# This script starts all required containers (except backend & frontend) and runs them locally

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Function to check if Docker is running
check_docker() {
    if ! docker info >/dev/null 2>&1; then
        print_error "Docker is not running or not accessible. Please start Docker first."
        exit 1
    fi
    print_success "Docker is running"
}

# Function to check if required tools are installed
check_requirements() {
    print_status "Checking requirements..."
    
    if ! command_exists docker; then
        print_error "Docker is not installed. Please install Docker first."
        exit 1
    fi
    
    if ! command_exists mvn; then
        print_error "Maven is not installed. Please install Maven first."
        exit 1
    fi
    
    if ! command_exists node; then
        print_error "Node.js is not installed. Please install Node.js first."
        exit 1
    fi
    
    if ! command_exists npm; then
        print_error "npm is not installed. Please install npm first."
        exit 1
    fi
    
    print_success "All requirements are met"
}

# Function to start containers (excluding backend and frontend)
start_containers() {
    print_status "Starting required containers (excluding backend and frontend)..."
    
    # Get the script directory
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    project_root="$(dirname "$script_dir")"
    
    # Change to project root
    cd "$project_root"
    
    # Start containers using docker compose with port mapping override
    # We'll use docker compose directly to have more control
    docker compose -f deployment/docker-compose/docker-compose.yaml -f deployment/docker-compose/docker-compose.override.yaml up -d mysql redis clickhouse zookeeper minio mc python-backend
    
    print_status "Waiting for containers to be healthy..."
    
    # Wait for containers to be healthy
    local max_retries=60
    local interval=2
    
    # Check MySQL
    print_status "Waiting for MySQL..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-mysql-1 2>/dev/null | grep -q "healthy"; then
            print_success "MySQL is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_error "MySQL failed to become healthy after ${max_retries}s"
            exit 1
        fi
        sleep $interval
    done
    
    # Check Redis
    print_status "Waiting for Redis..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-redis-1 2>/dev/null | grep -q "healthy"; then
            print_success "Redis is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_error "Redis failed to become healthy after ${max_retries}s"
            exit 1
        fi
        sleep $interval
    done
    
    # Check ClickHouse
    print_status "Waiting for ClickHouse..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-clickhouse-1 2>/dev/null | grep -q "healthy"; then
            print_success "ClickHouse is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_error "ClickHouse failed to become healthy after ${max_retries}s"
            exit 1
        fi
        sleep $interval
    done
    
    # Check Zookeeper
    print_status "Waiting for Zookeeper..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-zookeeper-1 2>/dev/null | grep -q "healthy"; then
            print_success "Zookeeper is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_error "Zookeeper failed to become healthy after ${max_retries}s"
            exit 1
        fi
        sleep $interval
    done
    
    # Check MinIO
    print_status "Waiting for MinIO..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-minio-1 2>/dev/null | grep -q "healthy"; then
            print_success "MinIO is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_error "MinIO failed to become healthy after ${max_retries}s"
            exit 1
        fi
        sleep $interval
    done
    
    # Check Python Backend
    print_status "Waiting for Python Backend..."
    for i in $(seq 1 $max_retries); do
        if docker inspect -f '{{.State.Health.Status}}' opik-python-backend-1 2>/dev/null | grep -q "healthy"; then
            print_success "Python Backend is healthy"
            break
        fi
        if [ $i -eq $max_retries ]; then
            print_warning "Python Backend may not be fully healthy, but continuing..."
            break
        fi
        sleep $interval
    done
    
    print_success "All required containers are running"
}

# Function to build backend with Maven
build_backend() {
    print_status "Building backend with Maven (skipping tests)..."
    
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    project_root="$(dirname "$script_dir")"
    backend_dir="$project_root/apps/opik-backend"
    
    cd "$backend_dir"
    
    # Clean and install, skipping tests
    mvn clean install -DskipTests
    
    if [ $? -eq 0 ]; then
        print_success "Backend built successfully"
    else
        print_error "Backend build failed"
        exit 1
    fi
}

# Function to run backend locally
run_backend() {
    print_status "Starting backend locally..."
    
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    project_root="$(dirname "$script_dir")"
    backend_dir="$project_root/apps/opik-backend"
    
    cd "$backend_dir"
    
    # Set environment variables for local development
    export STATE_DB_PROTOCOL="jdbc:mysql://"
    export STATE_DB_URL="localhost:3306/opik?createDatabaseIfNotExist=true&rewriteBatchedStatements=true"
    export STATE_DB_DATABASE_NAME="opik"
    export STATE_DB_USER="opik"
    export STATE_DB_PASS="opik"
    export ANALYTICS_DB_MIGRATIONS_URL="jdbc:clickhouse://localhost:8123"
    export ANALYTICS_DB_MIGRATIONS_USER="opik"
    export ANALYTICS_DB_MIGRATIONS_PASS="opik"
    export ANALYTICS_DB_PROTOCOL="HTTP"
    export ANALYTICS_DB_HOST="localhost"
    export ANALYTICS_DB_PORT="8123"
    export ANALYTICS_DB_DATABASE_NAME="opik"
    export ANALYTICS_DB_USERNAME="opik"
    export ANALYTICS_DB_PASS="opik"
    export JAVA_OPTS="-Dliquibase.propertySubstitutionEnabled=true -XX:+UseG1GC -XX:MaxRAMPercentage=80.0"
    export REDIS_URL="redis://:opik@localhost:6379/"
    export OPIK_OTEL_SDK_ENABLED="false"
    export OTEL_VERSION="2.16.0"
    export OTEL_PROPAGATORS="tracecontext,baggage,b3"
    export OTEL_EXPERIMENTAL_EXPORTER_OTLP_RETRY_ENABLED="true"
    export OTEL_EXPORTER_OTLP_METRICS_DEFAULT_HISTOGRAM_AGGREGATION="BASE2_EXPONENTIAL_BUCKET_HISTOGRAM"
    export OTEL_EXPERIMENTAL_RESOURCE_DISABLED_KEYS="process.command_args"
    export OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE="delta"
    export OPIK_USAGE_REPORT_ENABLED="true"
    export AWS_ACCESS_KEY_ID="THAAIOSFODNN7EXAMPLE"
    export AWS_SECRET_ACCESS_KEY="LESlrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    export PYTHON_EVALUATOR_URL="http://localhost:8000"
    export TOGGLE_GUARDRAILS_ENABLED="false"
    
    # Run database migrations first
    print_status "Running database migrations..."
    ./run_db_migrations.sh
    
    # Start the backend
    print_status "Starting backend server..."
    java $JAVA_OPTS -jar target/opik-backend-1.0-SNAPSHOT.jar server config.yml &
    BACKEND_PID=$!
    
    # Wait for backend to start
    print_status "Waiting for backend to start..."
    for i in $(seq 1 30); do
        if curl -f http://localhost:8080/health-check >/dev/null 2>&1; then
            print_success "Backend is running on http://localhost:8080"
            break
        fi
        if [ $i -eq 30 ]; then
            print_error "Backend failed to start after 30 attempts"
            kill $BACKEND_PID 2>/dev/null || true
            exit 1
        fi
        sleep 2
    done
}

# Function to run frontend locally
run_frontend() {
    print_status "Starting frontend locally..."
    
    script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    project_root="$(dirname "$script_dir")"
    frontend_dir="$project_root/apps/opik-frontend"
    
    cd "$frontend_dir"
    
    # Install dependencies if node_modules doesn't exist
    if [ ! -d "node_modules" ]; then
        print_status "Installing frontend dependencies..."
        npm install
    fi
    
    # Start the frontend development server
    print_status "Starting frontend development server..."
    npm start &
    FRONTEND_PID=$!
    
    # Wait for frontend to start
    print_status "Waiting for frontend to start..."
    for i in $(seq 1 30); do
        if curl -f http://localhost:5173 >/dev/null 2>&1; then
            print_success "Frontend is running on http://localhost:5173"
            break
        fi
        if [ $i -eq 30 ]; then
            print_error "Frontend failed to start after 30 attempts"
            kill $FRONTEND_PID 2>/dev/null || true
            exit 1
        fi
        sleep 2
    done
}

# Function to handle cleanup on script exit
cleanup() {
    print_status "Cleaning up..."
    
    # Kill background processes
    if [ ! -z "$BACKEND_PID" ]; then
        print_status "Stopping backend..."
        kill $BACKEND_PID 2>/dev/null || true
    fi
    
    if [ ! -z "$FRONTEND_PID" ]; then
        print_status "Stopping frontend..."
        kill $FRONTEND_PID 2>/dev/null || true
    fi
    
    print_success "Cleanup completed"
}

# Function to show usage
show_usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --containers-only    Only start containers, don't run backend/frontend locally"
    echo "  --backend-only       Only build and run backend locally"
    echo "  --frontend-only      Only run frontend locally"
    echo "  --help               Show this help message"
    echo ""
    echo "If no options are provided, the script will:"
    echo "  1. Start all required containers (excluding backend & frontend)"
    echo "  2. Build the backend with Maven (skipping tests)"
    echo "  3. Run backend and frontend locally"
}

# Set up signal handlers for cleanup
trap cleanup EXIT INT TERM

# Parse command line arguments
CONTAINERS_ONLY=false
BACKEND_ONLY=false
FRONTEND_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --containers-only)
            CONTAINERS_ONLY=true
            shift
            ;;
        --backend-only)
            BACKEND_ONLY=true
            shift
            ;;
        --frontend-only)
            FRONTEND_ONLY=true
            shift
            ;;
        --help)
            show_usage
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            show_usage
            exit 1
            ;;
    esac
done

# Main execution
main() {
    print_status "Starting OPIK local development environment..."
    
    # Check requirements
    check_requirements
    check_docker
    
    # Start containers
    start_containers
    
    if [ "$CONTAINERS_ONLY" = true ]; then
        print_success "Containers started successfully. Use --help for more options."
        return
    fi
    
    if [ "$BACKEND_ONLY" = true ]; then
        build_backend
        run_backend
        print_success "Backend is running locally. Press Ctrl+C to stop."
        wait $BACKEND_PID
        return
    fi
    
    if [ "$FRONTEND_ONLY" = true ]; then
        run_frontend
        print_success "Frontend is running locally. Press Ctrl+C to stop."
        wait $FRONTEND_PID
        return
    fi
    
    # Full setup: build and run both backend and frontend
    build_backend
    run_backend
    run_frontend
    
    print_success "OPIK local development environment is ready!"
    print_status "Backend: http://localhost:8080"
    print_status "Frontend: http://localhost:5173"
    print_status "Press Ctrl+C to stop all services"
    
    # Wait for user to stop
    wait
}

# Run main function
main "$@"