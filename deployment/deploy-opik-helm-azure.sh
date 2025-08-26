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
        "APP_GATEWAY_NAME"
        "VNET_NAME"
        "AKS_SUBNET_NAME"
        "APP_GATEWAY_SUBNET_NAME"
        "PUBLIC_IP_NAME"
        "VNET_ADDRESS_PREFIX"
        "AKS_SUBNET_PREFIX"
        "APPGW_SUBNET_PREFIX"
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

# Create networking infrastructure for Application Gateway
print_step "Setting up networking infrastructure"

# Create virtual network (if it doesn't exist)
if az network vnet show --name $VNET_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "Virtual network $VNET_NAME already exists"
else
    az network vnet create \
        --resource-group $RESOURCE_GROUP \
        --name $VNET_NAME \
        --address-prefix $VNET_ADDRESS_PREFIX \
        --location $LOCATION
    print_success "Created virtual network $VNET_NAME"
fi

# Create subnet for AKS (if it doesn't exist)
if az network vnet subnet show --vnet-name $VNET_NAME --name $AKS_SUBNET_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "AKS subnet $AKS_SUBNET_NAME already exists"
else
    az network vnet subnet create \
        --resource-group $RESOURCE_GROUP \
        --vnet-name $VNET_NAME \
        --name $AKS_SUBNET_NAME \
        --address-prefix $AKS_SUBNET_PREFIX
    print_success "Created AKS subnet $AKS_SUBNET_NAME"
fi

# Create subnet for Application Gateway (if it doesn't exist)
if az network vnet subnet show --vnet-name $VNET_NAME --name $APP_GATEWAY_SUBNET_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "Application Gateway subnet $APP_GATEWAY_SUBNET_NAME already exists"
else
    az network vnet subnet create \
        --resource-group $RESOURCE_GROUP \
        --vnet-name $VNET_NAME \
        --name $APP_GATEWAY_SUBNET_NAME \
        --address-prefix $APPGW_SUBNET_PREFIX
    print_success "Created Application Gateway subnet $APP_GATEWAY_SUBNET_NAME"
fi

# Get subnet ID for AKS
AKS_SUBNET_ID=$(az network vnet subnet show --resource-group $RESOURCE_GROUP --vnet-name $VNET_NAME --name $AKS_SUBNET_NAME --query id -o tsv)

# Create AKS cluster (if it doesn't exist)
print_step "Creating AKS Cluster"
if az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "AKS cluster $AKS_CLUSTER_NAME already exists"
    
    # Check if AKS is using the correct VNet
    AKS_VNET_SUBNET_ID=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "agentPoolProfiles[0].vnetSubnetId" -o tsv)
    if [ "$AKS_VNET_SUBNET_ID" != "$AKS_SUBNET_ID" ] && [ "$AKS_VNET_SUBNET_ID" != "null" ]; then
        print_warning "AKS cluster is using a different VNet. This may affect AGIC functionality."
    elif [ "$AKS_VNET_SUBNET_ID" == "null" ]; then
        print_warning "AKS cluster was created without VNet integration. Consider recreating for optimal AGIC functionality."
    fi
else
    print_step "Creating AKS cluster with VNet integration (this may take 10-15 minutes)..."
    az aks create \
        --resource-group $RESOURCE_GROUP \
        --name $AKS_CLUSTER_NAME \
        --node-count 3 \
        --node-vm-size Standard_D2s_v3 \
        --enable-addons monitoring \
        --generate-ssh-keys \
        --attach-acr $ACR_NAME \
        --location $LOCATION \
        --vnet-subnet-id $AKS_SUBNET_ID \
        --network-plugin azure \
        --service-cidr 10.1.0.0/16 \
        --dns-service-ip 10.1.0.10
    print_success "Created AKS cluster $AKS_CLUSTER_NAME with VNet integration"
fi

# Create public IP for Application Gateway (if it doesn't exist)
if az network public-ip show --name $PUBLIC_IP_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "Public IP $PUBLIC_IP_NAME already exists"
else
    az network public-ip create \
        --resource-group $RESOURCE_GROUP \
        --name $PUBLIC_IP_NAME \
        --allocation-method Static \
        --sku Standard \
        --location $LOCATION
    print_success "Created public IP $PUBLIC_IP_NAME"
fi

# Get the public IP address
PUBLIC_IP_ADDRESS=$(az network public-ip show --name $PUBLIC_IP_NAME --resource-group $RESOURCE_GROUP --query "ipAddress" --output tsv)
print_success "Public IP address: $PUBLIC_IP_ADDRESS"

# Setup Azure Entra ID authentication
print_step "Setting up Azure Entra ID authentication"

# Get tenant ID
TENANT_ID=$(az account show --query tenantId -o tsv)
print_success "Azure Tenant ID: $TENANT_ID"

# Create Microsoft Entra ID group for Opik access (if specified)
if [ -n "${OPIK_ACCESS_GROUP_NAME:-}" ]; then
    print_step "Checking Microsoft Entra ID group for Opik access"
    
    # Check if group already exists
    OPIK_ACCESS_GROUP_ID=$(az ad group list --display-name "$OPIK_ACCESS_GROUP_NAME" --query "[0].id" -o tsv)
    
    if [ -z "$OPIK_ACCESS_GROUP_ID" ] || [ "$OPIK_ACCESS_GROUP_ID" = "null" ]; then
        # Create the group
        OPIK_ACCESS_GROUP_ID=$(az ad group create \
            --display-name "$OPIK_ACCESS_GROUP_NAME" \
            --mail-nickname "$(echo "$OPIK_ACCESS_GROUP_NAME" | tr '[:upper:]' '[:lower:]' | tr ' ' '-')" \
            --query "id" -o tsv)
        print_success "Created Microsoft Entra ID group: $OPIK_ACCESS_GROUP_NAME (ID: $OPIK_ACCESS_GROUP_ID)"
        print_warning "⚠️  Remember to add your team members to this group in the Azure Portal!"
    else
        print_success "Found existing Microsoft Entra ID group: $OPIK_ACCESS_GROUP_NAME (ID: $OPIK_ACCESS_GROUP_ID)"
    fi
    
    export OPIK_ACCESS_GROUP_ID
else
    print_warning "OPIK_ACCESS_GROUP_NAME not specified in .env.azure - authentication will allow any user in your tenant"
fi

# Create App Registration for Opik authentication
OPIK_APP_NAME="${OPIK_APP_NAME:-opik-frontend-auth}"
print_step "Creating App Registration for Opik authentication"

# Check if app registration already exists
APP_ID=$(az ad app list --display-name "$OPIK_APP_NAME" --query "[0].appId" -o tsv)

if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
    # Determine redirect URL based on domain or public IP
    if [ -n "${DOMAIN_NAME:-}" ]; then
        REDIRECT_URL="http://$DOMAIN_NAME/oauth2/callback"
        HOME_PAGE_URL="http://$DOMAIN_NAME"
    else
        REDIRECT_URL="http://$PUBLIC_IP_ADDRESS/oauth2/callback"
        HOME_PAGE_URL="http://$PUBLIC_IP_ADDRESS"
    fi
    
    # Create the app registration
    APP_ID=$(az ad app create \
        --display-name "$OPIK_APP_NAME" \
        --web-redirect-uris "$REDIRECT_URL" \
        --web-home-page-url "$HOME_PAGE_URL" \
        --sign-in-audience "AzureADMyOrg" \
        --query "appId" -o tsv)
    
    print_success "Created App Registration: $OPIK_APP_NAME (App ID: $APP_ID)"
    
    # Create service principal
    az ad sp create --id $APP_ID
    print_success "Created service principal for app registration"
    
    # Generate client secret
    CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)
    print_success "Generated client secret for app registration"
    
    echo ""
    print_warning "⚠️  IMPORTANT: Save these authentication credentials securely:"
    echo "   App ID (Client ID): $APP_ID"
    echo "   Client Secret: $CLIENT_SECRET"
    echo "   Tenant ID: $TENANT_ID"
    echo "   Redirect URL: $REDIRECT_URL"
    echo ""
    
else
    print_warning "App Registration $OPIK_APP_NAME already exists (App ID: $APP_ID)"
    
    # Check if CLIENT_SECRET is already set
    if [ -z "${CLIENT_SECRET:-}" ]; then
        print_warning "CLIENT_SECRET not found in environment variables"
        echo ""
        read -p "Do you want to regenerate the client secret? (y/N): " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)
            print_success "Regenerated client secret: $CLIENT_SECRET"
        else
            print_error "Cannot proceed without CLIENT_SECRET. Please set it manually in .env.azure"
            exit 1
        fi
    else
        print_success "Using existing CLIENT_SECRET from environment"
    fi
fi

# Generate OAuth2 cookie secret if not provided
if [ -z "${OAUTH2_COOKIE_SECRET:-}" ]; then
    OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)
    print_success "Generated OAuth2 cookie secret"
else
    print_success "Using existing OAuth2 cookie secret from environment"
fi

# Export authentication variables for Helm values substitution
export APP_ID
export CLIENT_SECRET
export TENANT_ID
export OAUTH2_COOKIE_SECRET
export OPIK_ACCESS_GROUP_ID

# Persist authentication values to .env.azure for future use
print_step "Updating .env.azure with generated authentication values..."
sed -i.bak \
    -e "s|^APP_ID=.*|APP_ID=\"$APP_ID\"|" \
    -e "s|^CLIENT_SECRET=.*|CLIENT_SECRET=\"$CLIENT_SECRET\"|" \
    -e "s|^TENANT_ID=.*|TENANT_ID=\"$TENANT_ID\"|" \
    -e "s|^OAUTH2_COOKIE_SECRET=.*|OAUTH2_COOKIE_SECRET=\"$OAUTH2_COOKIE_SECRET\"|" \
    .env.azure && rm .env.azure.bak
print_success "Authentication values saved to .env.azure"

print_success "Azure Entra ID authentication setup completed"

# Create Application Gateway (if it doesn't exist)
if az network application-gateway show --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "Application Gateway $APP_GATEWAY_NAME already exists"
else
    print_step "Creating Application Gateway (this may take 5-10 minutes)..."
    az network application-gateway create \
        --name $APP_GATEWAY_NAME \
        --location $LOCATION \
        --resource-group $RESOURCE_GROUP \
        --capacity 2 \
        --sku Standard_v2 \
        --http-settings-cookie-based-affinity Disabled \
        --frontend-port 80 \
        --http-settings-port 80 \
        --http-settings-protocol Http \
        --public-ip-address $PUBLIC_IP_NAME \
        --vnet-name $VNET_NAME \
        --subnet $APP_GATEWAY_SUBNET_NAME \
        --priority 1000
    print_success "Created Application Gateway $APP_GATEWAY_NAME"
fi

# Get AKS credentials
print_step "Getting AKS credentials"
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_CLUSTER_NAME --overwrite-existing
print_success "Retrieved AKS credentials"

# Test cluster connection
kubectl cluster-info
print_success "Connected to AKS cluster"

# Install and configure Azure Application Gateway Ingress Controller (AGIC)
print_step "Installing Azure Application Gateway Ingress Controller (AGIC)"

# Enable AGIC add-on for AKS
APP_GATEWAY_ID=$(az network application-gateway show --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "id" --output tsv)

# Check if AGIC is already enabled
AGIC_ENABLED=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "addonProfiles.ingressApplicationGateway.enabled" --output tsv 2>/dev/null || echo "false")

if [ "$AGIC_ENABLED" = "true" ]; then
    print_warning "AGIC add-on is already enabled on AKS cluster"
else
    print_step "Enabling AGIC add-on on AKS cluster..."
    az aks enable-addons \
        --resource-group $RESOURCE_GROUP \
        --name $AKS_CLUSTER_NAME \
        --addons ingress-appgw \
        --appgw-id $APP_GATEWAY_ID
    print_success "Enabled AGIC add-on on AKS cluster"
fi

# Wait for AGIC to be ready
print_step "Waiting for AGIC to be ready..."
kubectl wait --for=condition=Ready pod -l app=ingress-appgw -n kube-system --timeout=300s
print_success "AGIC is ready"

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
helm repo add oauth2-proxy https://oauth2-proxy.github.io/manifests
helm repo update

# Update dependencies to latest versions and rebuild lock file
helm dependency update

# Build dependencies from updated lock file (ensures consistency)
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
export DOMAIN_NAME
export PUBLIC_IP_ADDRESS
export NAMESPACE

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
print_success "Opik deployment has been initiated on your AKS cluster with Application Gateway and Azure Entra ID authentication!"
echo ""
echo "🔐 Authentication Setup:"
echo "   • Azure Entra ID authentication is enabled"
echo "   • App Registration: $OPIK_APP_NAME (ID: $APP_ID)"
if [ -n "${OPIK_ACCESS_GROUP_ID:-}" ]; then
    echo "   • Access restricted to group: $OPIK_ACCESS_GROUP_NAME (ID: $OPIK_ACCESS_GROUP_ID)"
    echo "   • ⚠️  Add team members to this group in Azure Portal!"
else
    echo "   • Access allowed for all users in tenant: $TENANT_ID"
fi
echo ""
echo "Access Options:"
echo ""
if [ -n "$DOMAIN_NAME" ]; then
    echo "1. Domain Access (after DNS configuration):"
    echo "   http://$DOMAIN_NAME"
    echo ""
    echo "   To configure DNS, point your domain to: $PUBLIC_IP_ADDRESS"
    echo ""
fi
echo "2. Direct IP Access:"
echo "   http://$PUBLIC_IP_ADDRESS"
echo ""
echo "   • Users will be redirected to Microsoft login"
echo "   • After authentication, they'll be redirected back to Opik"
echo ""
echo "3. Port forward to your local machine (bypasses authentication):"
echo "   kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173"
echo "   Then open: http://localhost:5173"
echo ""
echo "To check the status of your deployment:"
echo "   kubectl get pods -n $NAMESPACE"
echo "   kubectl get ingress -n $NAMESPACE"
echo "   kubectl logs -n $NAMESPACE deployment/oauth2-proxy"
echo ""
echo "To check Application Gateway status:"
echo "   az network application-gateway show --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP"
echo ""
echo "To view logs:"
echo "   kubectl logs -n $NAMESPACE deployment/opik-backend"
echo "   kubectl logs -n $NAMESPACE deployment/opik-frontend"
echo "   kubectl logs -n $NAMESPACE deployment/opik-python-backend"
echo "   kubectl logs -n $NAMESPACE deployment/oauth2-proxy"
echo "   kubectl logs -n kube-system -l app=ingress-appgw"
echo ""
echo "To manage team access:"
echo "   # Add user to access group"
echo "   az ad group member add --group '$OPIK_ACCESS_GROUP_NAME' --member-id <user-email-or-object-id>"
echo "   # List current group members"
echo "   az ad group member list --group '$OPIK_ACCESS_GROUP_NAME' --query '[].userPrincipalName'"
echo ""
echo "To uninstall Opik:"
echo "   helm uninstall opik -n $NAMESPACE"
echo ""

# Optionally start port forwarding or show access info
echo "Choose how you want to access Opik:"
echo "1. Use Application Gateway (recommended) - Access via public IP or domain"
echo "2. Use port forwarding - Access via localhost"
echo ""
read -p "Enter your choice (1 or 2): " -n 1 -r
echo
if [[ $REPLY == "2" ]]; then
    print_step "Starting port forwarding"
    echo "Opik will be available at http://localhost:5173"
    echo "Press Ctrl+C to stop port forwarding"
    kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173
else
    print_step "Application Gateway Access Information"
    echo ""
    if [ -n "$DOMAIN_NAME" ]; then
        print_success "Your application will be available at: http://$DOMAIN_NAME"
        echo "Make sure to configure DNS to point $DOMAIN_NAME to $PUBLIC_IP_ADDRESS"
    else
        print_success "Your application will be available at: http://$PUBLIC_IP_ADDRESS"
    fi
    echo ""
    echo "It may take a few minutes for the Application Gateway to configure the backend pools."
    echo "If you get 502 errors, wait a few minutes and try again."
fi

print_success "Deployment script completed successfully!"