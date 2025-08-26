#!/bin/bash

# Opik Helm Deployment Script for Azure
# This script creates Azure resources, builds images, and deploys Opik using Helm

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_step() {
    echo -e "${BLUE}==== $1 ====${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

# Load environment variables from .env.azure
if [ -f ".env.azure" ]; then
    print_step "Loading environment variables from .env.azure"
    
    # Source the file directly after filtering out comments and empty lines
    set -a  # Automatically export all variables
    while IFS= read -r line; do
        # Skip empty lines and comments
        if [[ -n "$line" && ! "$line" =~ ^[[:space:]]*# ]]; then
            # If line contains =, evaluate it to handle quotes properly
            if [[ "$line" =~ = ]]; then
                eval "$line"
            fi
        fi
    done < .env.azure
    set +a  # Turn off automatic export
    
    # Validate required variables
    REQUIRED_VARS=(
        "RESOURCE_GROUP"
        "LOCATION" 
        "AKS_CLUSTER_NAME"
        "ACR_NAME"
        "NAMESPACE"
        "OPIK_VERSION"
    )
    
    MISSING_VARS=()
    for var in "${REQUIRED_VARS[@]}"; do
        if [ -z "${!var}" ]; then
            MISSING_VARS+=("$var")
        fi
    done
    
    if [ ${#MISSING_VARS[@]} -ne 0 ]; then
        print_error "Missing required environment variables in .env.azure:"
        for var in "${MISSING_VARS[@]}"; do
            print_error "  - $var"
        done
        print_error ""
        print_error "Current values loaded:"
        for var in "${REQUIRED_VARS[@]}"; do
            echo "  $var='${!var}'"
        done
        exit 1
    fi
    
    print_success "Loaded and validated environment variables from .env.azure"
else
    print_error ".env.azure file not found! Please make sure you're running this from the deployment directory."
    exit 1
fi

print_step "Starting Opik Helm deployment to Azure"
echo "Azure Resource Group (ARG) Name: $RESOURCE_GROUP"
echo "Azure Container Registry (ACR) Name: $ACR_NAME"
echo "Azure Kubernetes Service (AKS) Cluster Name: $AKS_CLUSTER_NAME"
echo "Azure Location: $LOCATION"
echo "Built Images Version: $OPIK_VERSION"
echo "Kubernetes Namespace: $NAMESPACE"
echo ""

# Check prerequisites
print_step "Checking prerequisites"

# Check if helm-values-azure-template.yaml exists
if [ ! -f "helm-values-azure-template.yaml" ]; then
    print_error "helm-values-azure-template.yaml file not found!"
    print_error "Please ensure this file exists in the deployment directory."
    print_error "This file should contain the custom Helm values template for your Azure deployment."
    exit 1
fi

# Check if Azure CLI is installed and logged in
if ! command -v az &> /dev/null; then
    print_error "Azure CLI not found. Please install it from https://docs.microsoft.com/en-us/cli/azure/install-azure-cli"
    exit 1
fi

# Check if logged into Azure
if ! az account show &> /dev/null; then
    print_error "Not logged into Azure. Please run 'az login' first."
    exit 1
fi

# Check if Docker is running
if ! docker ps &> /dev/null; then
    print_error "Docker is not running. Please start Docker."
    exit 1
fi

# Check if kubectl is installed
if ! command -v kubectl &> /dev/null; then
    print_error "kubectl not found. Please install it from https://kubernetes.io/docs/tasks/tools/"
    exit 1
fi

# Check if Helm is installed
if ! command -v helm &> /dev/null; then
    print_error "Helm not found. Please install it from https://helm.sh/docs/intro/install/"
    exit 1
fi

# Check if envsubst is installed (for environment variable substitution)
if ! command -v envsubst &> /dev/null; then
    print_error "envsubst not found. Please install gettext package:"
    print_error "  macOS: brew install gettext"
    print_error "  Ubuntu/Debian: apt-get install gettext-base"
    print_error "  CentOS/RHEL: yum install gettext"
    exit 1
fi

print_success "All prerequisites check passed"

# Create Resource Group (if it doesn't exist)
print_step "Creating Resource Group"
if az group show --name $RESOURCE_GROUP &> /dev/null; then
    print_warning "Resource group $RESOURCE_GROUP already exists"
else
    az group create --name $RESOURCE_GROUP --location $LOCATION
    print_success "Created resource group $RESOURCE_GROUP"
fi

# Create Azure Container Registry (if it doesn't exist)
print_step "Creating Azure Container Registry"
if az acr show --name $ACR_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "ACR $ACR_NAME already exists"
else
    az acr create --resource-group $RESOURCE_GROUP --name $ACR_NAME --sku Basic --location $LOCATION
    print_success "Created ACR $ACR_NAME"
fi

# Enable admin user for ACR
az acr update -n $ACR_NAME --admin-enabled true
print_success "Enabled admin user for ACR"

# Login to ACR
print_step "Logging into Azure Container Registry"
az acr login --name $ACR_NAME
print_success "Logged into ACR"

# Create AKS cluster (if it doesn't exist)
print_step "Creating AKS Cluster"
if az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "AKS cluster $AKS_CLUSTER_NAME already exists"
else
    print_step "Creating AKS cluster (this may take 10-15 minutes)..."
    az aks create \
        --resource-group $RESOURCE_GROUP \
        --name $AKS_CLUSTER_NAME \
        --node-count 3 \
        --node-vm-size Standard_D2s_v3 \
        --enable-addons monitoring \
        --generate-ssh-keys \
        --attach-acr $ACR_NAME \
        --location $LOCATION
    print_success "Created AKS cluster $AKS_CLUSTER_NAME"
fi

# Get AKS credentials
print_step "Getting AKS credentials"
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_CLUSTER_NAME --overwrite-existing
print_success "Retrieved AKS credentials"

# Test cluster connection
kubectl cluster-info
print_success "Connected to AKS cluster"

# Build and push all images
print_step "Building and pushing Docker images"

# Get ACR login server
ACR_LOGIN_SERVER=$(az acr show --name $ACR_NAME --resource-group $RESOURCE_GROUP --query "loginServer" --output tsv)
print_success "ACR login server: $ACR_LOGIN_SERVER"

# Go to the opik root directory (one level up from deployment)
cd ..

# Build backend image with proper build args
print_step "Building opik-backend image for linux/amd64"
docker build --platform linux/amd64 \
    --build-arg OPIK_VERSION=$OPIK_VERSION \
    -t $ACR_LOGIN_SERVER/opik-backend:$OPIK_VERSION \
    ./apps/opik-backend
docker push $ACR_LOGIN_SERVER/opik-backend:$OPIK_VERSION
print_success "Built and pushed opik-backend:$OPIK_VERSION"

# Build python-backend image with proper build args
print_step "Building opik-python-backend image for linux/amd64"
docker build --platform linux/amd64 \
    --build-arg OPIK_VERSION=$OPIK_VERSION \
    -t $ACR_LOGIN_SERVER/opik-python-backend:$OPIK_VERSION \
    ./apps/opik-python-backend
docker push $ACR_LOGIN_SERVER/opik-python-backend:$OPIK_VERSION
print_success "Built and pushed opik-python-backend:$OPIK_VERSION"

# Build frontend image
print_step "Building opik-frontend image for linux/amd64"
docker build --platform linux/amd64 \
    -t $ACR_LOGIN_SERVER/opik-frontend:$OPIK_VERSION \
    ./apps/opik-frontend
docker push $ACR_LOGIN_SERVER/opik-frontend:$OPIK_VERSION
print_success "Built and pushed opik-frontend:$OPIK_VERSION"

# Build sandbox executor image
print_step "Building opik-sandbox-executor-python image for linux/amd64"
docker build --platform linux/amd64 \
    -t $ACR_LOGIN_SERVER/opik-sandbox-executor-python:$OPIK_VERSION \
    ./apps/opik-sandbox-executor-python
docker push $ACR_LOGIN_SERVER/opik-sandbox-executor-python:$OPIK_VERSION
print_success "Built and pushed opik-sandbox-executor-python:$OPIK_VERSION"

# Build guardrails image (optional)
if [ "${TOGGLE_GUARDRAILS_ENABLED:-false}" = "true" ]; then
    print_step "Building opik-guardrails-backend image for linux/amd64"
    docker build --platform linux/amd64 \
        --build-arg OPIK_VERSION=$OPIK_VERSION \
        -t $ACR_LOGIN_SERVER/opik-guardrails-backend:$OPIK_VERSION \
        ./apps/opik-guardrails-backend
    docker push $ACR_LOGIN_SERVER/opik-guardrails-backend:$OPIK_VERSION
    print_success "Built and pushed opik-guardrails-backend:$OPIK_VERSION"
fi

# Return to deployment directory
cd deployment

# Add Helm repositories and update dependencies
print_step "Setting up Helm dependencies"
cd helm_chart/opik

# Add required Helm repositories
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add altinity https://docs.altinity.com/clickhouse-operator/
helm repo update

# Build dependencies
helm dependency build

cd ../../

# Install/upgrade Opik using local Helm chart
print_step "Installing Opik using Helm"

# Create namespace if it doesn't exist
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Substitute environment variables in helm-values-azure-template.yaml
print_step "Preparing Helm values with environment variables"
export ACR_LOGIN_SERVER
export OPIK_VERSION
export TOGGLE_GUARDRAILS_ENABLED

# Create a temporary values file with substituted variables
envsubst < helm-values-azure-template.yaml > helm-values-azure-resolved.yaml

print_success "Environment variables substituted in Helm values"

# Install or upgrade Opik without --wait for better monitoring
helm upgrade --install opik ./helm_chart/opik \
    --namespace $NAMESPACE \
    --values helm-values-azure-resolved.yaml \
    --debug \
    --timeout 5m

print_success "Helm installation initiated"

# Clean up temporary file
rm -f helm-values-azure-resolved.yaml

# Monitor deployment progress
print_step "Monitoring deployment progress"
echo "This may take 2-5 minutes for all services to start..."
echo ""
echo "Checking pod status in 1 minute..."

# Wait for 1 minute before checking pod status
sleep 60
kubectl get pods -n $NAMESPACE

# Get service information
print_step "Getting service information"
kubectl get services -n $NAMESPACE

# Print access instructions
print_step "Access Instructions"
echo ""
print_success "Opik deployment has been initiated on your AKS cluster!"
echo ""
echo "To access Opik frontend:"
echo "1. Port forward to your local machine:"
echo "   kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173"
echo ""
echo "2. Open your browser and go to: http://localhost:5173"
echo ""
echo "To check the status of your deployment:"
echo "   kubectl get pods -n $NAMESPACE"
echo ""
echo "To view logs:"
echo "   kubectl logs -n $NAMESPACE deployment/opik-backend"
echo "   kubectl logs -n $NAMESPACE deployment/opik-frontend"
echo "   kubectl logs -n $NAMESPACE deployment/opik-python-backend"
echo ""
echo "To uninstall Opik:"
echo "   helm uninstall opik -n $NAMESPACE"
echo ""

# Optionally start port forwarding
read -p "Would you like to start port forwarding now? (y/n): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    print_step "Starting port forwarding"
    echo "Opik will be available at http://localhost:5173"
    echo "Press Ctrl+C to stop port forwarding"
    kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173
fi

print_success "Deployment script completed successfully!"