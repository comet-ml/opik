#!/bin/bash

# Opik Helm Deployment Script for Azure
# This script creates Azure resources, builds images, and deploys Opik using Helm
#
# HTTPS & OAuth2 Configuration:
# - Creates TLS secrets for AGIC to enable HTTPS listeners
# - Configures OAuth2 proxy with proper Azure Entra ID authentication
# - Uses AGIC annotations for SSL certificate management
# - Includes connectivity testing to verify HTTPS and OAuth2 functionality
#
# Key fixes implemented:
# 1. AGIC HTTPS compatibility: Uses Ingress TLS configuration instead of manual Application Gateway setup
# 2. OAuth2 reliability: Improved secret management and environment variable handling
# 3. Connectivity verification: Tests HTTPS and authentication redirection
# 4. Improved error handling: Better retry logic and conflict resolution

set -e  # Exit on error

# Function to cleanup resources from failed runs
cleanup_failed_deployment() {
    local namespace="$1"
    print_step "Cleaning up resources from previous failed deployments"
    
    # Remove any stuck TLS secrets (OAuth2 secrets now managed by chart)
    kubectl delete secret opik-tls-secret -n "$namespace" --ignore-not-found=true
    
    # Remove any stuck PVCs that could cause immutable field errors
    print_info "Force deleting PVCs (this will remove all data)..."
    for pvc_name in "opik-minio" "data-opik-clickhouse-cluster-0-0-0" "data-opik-mysql-0"; do
        if kubectl get pvc "$pvc_name" -n "$namespace" &>/dev/null; then
            print_info "Force deleting PVC: $pvc_name"
            kubectl patch pvc "$pvc_name" -n "$namespace" -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
            kubectl delete pvc "$pvc_name" -n "$namespace" --force --grace-period=0 &>/dev/null || true
        fi
    done
    
    # Also cleanup any ClickHouse PVCs
    kubectl get pvc -n "$namespace" -o name 2>/dev/null | grep "storage-vc-template-chi" | while read pvc_path; do
        pvc_name=$(echo "$pvc_path" | sed 's|persistentvolumeclaim/||')
        print_info "Force deleting ClickHouse PVC: $pvc_name"
        kubectl patch pvc "$pvc_name" -n "$namespace" -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
        kubectl delete pvc "$pvc_name" -n "$namespace" --force --grace-period=0 &>/dev/null || true
    done
    
    print_warning "⚠️  PVC cleanup will delete all stored data! Only use for clean redeployments."
    print_success "Cleanup completed"
}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_step() {
    echo -e "\n${BLUE}==== $1 ====${NC}"
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

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

print_header() {
    echo -e "\n${BLUE}╔══════════════════════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║ $1${NC}"
    echo -e "${BLUE}╚══════════════════════════════════════════════════════════════════════════════╝${NC}"
}

print_section() {
    echo -e "\n${YELLOW}┌─ $1 ─${NC}"
}

print_key_value() {
    local key="$1"
    local value="$2"
    printf "   %-20s: %s\n" "$key" "$value"
}

# Check for cleanup flag
if [ "${1:-}" = "--cleanup" ]; then
    if [ -f ".env.azure" ]; then
        source .env.azure
        if [ -n "${NAMESPACE:-}" ]; then
            cleanup_failed_deployment "$NAMESPACE"
            exit 0
        else
            print_error "NAMESPACE not found in .env.azure"
            exit 1
        fi
    else
        print_error ".env.azure file not found"
        exit 1
    fi
fi

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

# Force unset any OAuth2 cookie secret from previous runs to ensure fresh generation
unset OAUTH2_COOKIE_SECRET

echo "Azure Resource Group (ARG) Name: $RESOURCE_GROUP"
echo "Azure Container Registry (ACR) Name: $ACR_NAME"
echo "Azure Kubernetes Service (AKS) Cluster Name: $AKS_CLUSTER_NAME"
echo "Azure Location: $LOCATION"
echo "Built Images Version: $OPIK_VERSION"
echo "Kubernetes Namespace: $NAMESPACE"
echo ""

# Check for potential conflicts from previous runs
if kubectl get namespace "$NAMESPACE" &>/dev/null; then
    print_info "Namespace $NAMESPACE already exists - checking for potential conflicts..."
    
    EXISTING_SECRETS=$(kubectl get secret -n "$NAMESPACE" 2>/dev/null | grep -E "(opik-oauth2-proxy|opik-tls-secret)" | wc -l || echo "0")
    
    if [ "$EXISTING_SECRETS" -gt 0 ]; then
        print_warning "Found existing secrets that might conflict with Helm deployment:"
        kubectl get secret -n "$NAMESPACE" | grep -E "(opik-oauth2-proxy|opik-tls-secret)" || true
        echo ""
        print_info "These secrets will be cleaned up automatically before Helm deployment"
    fi
fi

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
        --zone 1 \
        --location $LOCATION
    print_success "Created public IP $PUBLIC_IP_NAME"
fi

# Get the public IP address
PUBLIC_IP_ADDRESS=$(az network public-ip show --name $PUBLIC_IP_NAME --resource-group $RESOURCE_GROUP --query "ipAddress" --output tsv)
print_success "Public IP address: $PUBLIC_IP_ADDRESS"

# Setup Azure Entra ID authentication
print_step "Setting up Azure Entra ID authentication"

# Function to retry admin consent with exponential backoff
grant_admin_consent_with_retry() {
    local app_id="$1"
    local max_attempts=5
    local attempt=1
    local wait_time=10
    
    while [ $attempt -le $max_attempts ]; do
        print_info "Attempt $attempt/$max_attempts: Granting admin consent..."
        
        # Try admin consent
        if az ad app permission admin-consent --id "$app_id" &>/dev/null; then
            print_success "Admin consent granted successfully"
            
            # Also try the permission grant (this sometimes helps with propagation)
            if az ad app permission grant --id "$app_id" --api 00000003-0000-0000-c000-000000000000 &>/dev/null; then
                print_success "Permission grant completed successfully"
            else
                print_warning "Permission grant had issues but admin consent succeeded"
            fi
            return 0
        else
            if [ $attempt -eq $max_attempts ]; then
                print_error "Failed to grant admin consent after $max_attempts attempts"
                print_warning "You may need to grant consent manually in Azure Portal"
                print_info "Go to: https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationMenuBlade/~/CallAnAPI/appId/$app_id"
                return 1
            else
                print_warning "Attempt $attempt failed - Azure AD may still be propagating the app registration"
                print_info "Waiting $wait_time seconds before retry..."
                sleep $wait_time
                wait_time=$((wait_time * 2))  # Exponential backoff
                attempt=$((attempt + 1))
            fi
        fi
    done
}

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
OPIK_APP_NAME="${OPIK_APP_NAME:-opik-frontend-auth-${RESOURCE_GROUP}}"
print_step "Creating App Registration for Opik authentication"
print_info "App Registration Name: $OPIK_APP_NAME"

# Check if app registration already exists
print_step "Searching for existing App Registration with name: $OPIK_APP_NAME"
APP_ID=$(az ad app list --display-name "$OPIK_APP_NAME" --query "[0].appId" -o tsv)

print_info "Search result for APP_ID: '$APP_ID'"

if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
    print_step "No existing app registration found - creating new one"
    # Determine redirect URL based on domain or public IP
    if [ -n "${DOMAIN_NAME:-}" ]; then
        REDIRECT_URL="https://$DOMAIN_NAME/oauth2/callback"
        HOME_PAGE_URL="https://$DOMAIN_NAME"
    else
        REDIRECT_URL="https://$PUBLIC_IP_ADDRESS/oauth2/callback"
        HOME_PAGE_URL="https://$PUBLIC_IP_ADDRESS"
    fi
    
    # Create the app registration
    APP_ID=$(az ad app create \
        --display-name "$OPIK_APP_NAME" \
        --web-redirect-uris "$REDIRECT_URL" \
        --web-home-page-url "$HOME_PAGE_URL" \
        --sign-in-audience "AzureADMyOrg" \
        --query "appId" -o tsv)
    
    print_success "Created App Registration: $OPIK_APP_NAME (App ID: $APP_ID)"
    
    # Add required Microsoft Graph permissions
    # Note:
    # 00000003-0000-0000-c000-000000000000 = Microsoft Graph API (fixed GUID)
    # e1fe6dd8-ba31-4d61-89e7-88639da4683d = User.Read permission (fixed GUID)
    # Scope = Delegated permission (acts on behalf of signed-in user)
    print_step "Configuring Microsoft Graph permissions"
    az ad app permission add \
        --id $APP_ID \
        --api 00000003-0000-0000-c000-000000000000 \
        --api-permissions e1fe6dd8-ba31-4d61-89e7-88639da4683d=Scope
    
    print_success "Added Microsoft Graph User.Read permission"
    
    # Grant admin consent for the permissions with retry logic for propagation delays
    print_step "Granting admin consent for permissions (waiting for Azure AD propagation)"
    
    # Call the retry function
    grant_admin_consent_with_retry "$APP_ID"
        
    # Create service principal (check if it already exists first)
    print_step "Creating service principal"
    
    # Add a more intelligent wait for Azure AD propagation
    print_info "Waiting for Azure AD propagation (this may take 30-60 seconds)..."
    MAX_SP_ATTEMPTS=6
    SP_ATTEMPT=1
    
    while [ $SP_ATTEMPT -le $MAX_SP_ATTEMPTS ]; do
        print_info "Service principal creation attempt $SP_ATTEMPT/$MAX_SP_ATTEMPTS..."
        
        # Check if service principal already exists
        EXISTING_SP=$(az ad sp show --id $APP_ID --query "appId" -o tsv 2>/dev/null)
        
        if [ -n "$EXISTING_SP" ] && [ "$EXISTING_SP" != "null" ]; then
            print_success "Service principal already exists for this app registration"
            break
        fi
        
        # Try to create the service principal
        if az ad sp create --id $APP_ID &>/dev/null; then
            print_success "Created service principal for app registration"
            break
        else
            if [ $SP_ATTEMPT -eq $MAX_SP_ATTEMPTS ]; then
                print_error "Failed to create service principal after $MAX_SP_ATTEMPTS attempts"
                print_warning "Azure AD may still be propagating the app registration"
                print_info "You can try running the script again in a few minutes"
                exit 1
            else
                print_warning "Service principal creation failed (attempt $SP_ATTEMPT) - Azure AD may still be propagating"
                print_info "Waiting 30 seconds before retry..."
                sleep 30
                SP_ATTEMPT=$((SP_ATTEMPT + 1))
            fi
        fi
    done
    
    # Generate client secret
    print_step "Generating client secret"
    CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)

    print_success "Generated client secret for app registration"

    # Display authentication credentials in a standardized format
    print_section "Authentication Credentials Created"
    print_key_value "App ID" "$APP_ID"
    print_key_value "Client Secret" "$CLIENT_SECRET"
    print_key_value "Tenant ID" "$TENANT_ID"
    print_key_value "Redirect URL" "$REDIRECT_URL"
    print_warning "⚠️  IMPORTANT: Save these credentials securely - they will not be stored in files!"
    
else
    print_warning "App Registration $OPIK_APP_NAME already exists (App ID: $APP_ID)"
    print_info "Found existing App Registration with ID: $APP_ID"
    
    # Check and add Microsoft Graph permissions if missing
    print_step "Checking Microsoft Graph permissions"
    EXISTING_PERMISSIONS=$(az ad app permission list --id $APP_ID --query "[?resourceAppId=='00000003-0000-0000-c000-000000000000'].resourceAccess[?id=='e1fe6dd8-ba31-4d61-89e7-88639da4683d']" -o tsv)
    # Query explanation: Check if Microsoft Graph API (00000003...) has User.Read permission (e1fe6dd8...)
    
    if [ -z "$EXISTING_PERMISSIONS" ]; then
        print_warning "Microsoft Graph User.Read permission missing - adding it now"
        az ad app permission add \
            --id $APP_ID \
            --api 00000003-0000-0000-c000-000000000000 \
            --api-permissions e1fe6dd8-ba31-4d61-89e7-88639da4683d=Scope

        # Grant admin consent with retry (reuse the function defined above)
        print_step "Granting admin consent for added permissions"
        grant_admin_consent_with_retry "$APP_ID"
        print_success "Added and consented Microsoft Graph User.Read permission"
    else
        print_success "Microsoft Graph permissions already configured"
    fi
    
    # Check if CLIENT_SECRET is already set
    if [ -z "${CLIENT_SECRET:-}" ]; then
        print_warning "CLIENT_SECRET not found in environment variables - regenerating client secret"
        
        # Generate new client secret
        CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)
        
        if [ -n "$CLIENT_SECRET" ]; then
            print_success "Regenerated client secret for existing app registration"
            
            # Display updated credentials
            print_section "Authentication Credentials (Regenerated)"
            print_key_value "App ID" "$APP_ID"
            print_key_value "Client Secret" "$CLIENT_SECRET"
            print_key_value "Tenant ID" "$TENANT_ID"
            print_warning "⚠️  IMPORTANT: Client secret has been regenerated - update your records!"
        else
            print_error "Failed to regenerate client secret - deployment cannot continue"
            exit 1
        fi
    else
        print_success "Using existing CLIENT_SECRET from environment"
        
        # Display existing credentials (without showing the secret)
        print_section "Authentication Credentials (Existing)"
        print_key_value "App ID" "$APP_ID"
        print_key_value "Client Secret" "****** (existing)"
        print_key_value "Tenant ID" "$TENANT_ID"
    fi
fi

# Generate OAuth2 cookie secret if not provided or if existing one is invalid
if [ -z "${OAUTH2_COOKIE_SECRET:-}" ]; then
    # Generate exactly 32 bytes for AES cipher compatibility
    OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)  # 16 bytes = 32 hex characters
    print_success "Generated OAuth2 cookie secret (32 bytes)"
    print_section "OAuth2 Configuration"
    print_key_value "Cookie Secret" "$OAUTH2_COOKIE_SECRET"
else
    # Validate existing cookie secret length
    COOKIE_SECRET_LENGTH=${#OAUTH2_COOKIE_SECRET}
    if [ "$COOKIE_SECRET_LENGTH" -ne 32 ]; then
        print_warning "Existing OAuth2 cookie secret is $COOKIE_SECRET_LENGTH bytes, but 32 bytes required"
        print_step "Regenerating OAuth2 cookie secret with correct length"
        OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)  # 16 bytes = 32 hex characters
        print_success "Generated new OAuth2 cookie secret (32 bytes)"
        print_section "OAuth2 Configuration"
        print_key_value "Cookie Secret" "$OAUTH2_COOKIE_SECRET"
    else
        print_success "Using existing OAuth2 cookie secret from environment (32 bytes)"
    fi
fi

# Export authentication variables for Helm values substitution
print_step "Exporting authentication variables for Helm"
print_info "APP_ID being exported: $APP_ID"
print_info "CLIENT_SECRET length: ${#CLIENT_SECRET} characters"
print_info "TENANT_ID being exported: $TENANT_ID"
print_info "PUBLIC_IP_ADDRESS being exported: $PUBLIC_IP_ADDRESS"

export APP_ID
export CLIENT_SECRET
export TENANT_ID
export OAUTH2_COOKIE_SECRET
export OPIK_ACCESS_GROUP_ID

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

# HTTPS will be configured automatically by AGIC based on Ingress TLS configuration
print_step "HTTPS will be configured by AGIC during deployment"
print_info "AGIC will automatically create HTTPS infrastructure based on Ingress TLS configuration"

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
    
    # Check for any in-progress operations and wait for them to complete
    print_info "Checking for any in-progress AKS operations..."
    AKS_OPERATION_STATUS=""
    MAX_WAIT_TIME=600  # 10 minutes
    WAIT_TIME=0
    
    while [ $WAIT_TIME -lt $MAX_WAIT_TIME ]; do
        AKS_OPERATION_STATUS=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "provisioningState" -o tsv 2>/dev/null)
        
        if [ "$AKS_OPERATION_STATUS" = "Succeeded" ]; then
            print_success "AKS cluster is ready for AGIC enablement"
            break
        elif [ "$AKS_OPERATION_STATUS" = "Updating" ] || [ "$AKS_OPERATION_STATUS" = "Creating" ]; then
            print_info "AKS cluster operation in progress (status: $AKS_OPERATION_STATUS). Waiting 30 seconds..."
            sleep 30
            WAIT_TIME=$((WAIT_TIME + 30))
        else
            print_warning "AKS cluster status: $AKS_OPERATION_STATUS. Proceeding with AGIC enablement..."
            break
        fi
    done
    
    if [ $WAIT_TIME -ge $MAX_WAIT_TIME ]; then
        print_error "AKS cluster operation did not complete within 10 minutes. Please check Azure portal or try again later."
        exit 1
    fi
    
    # Now try to enable AGIC
    if az aks enable-addons \
        --resource-group $RESOURCE_GROUP \
        --name $AKS_CLUSTER_NAME \
        --addons ingress-appgw \
        --appgw-id $APP_GATEWAY_ID 2>/dev/null; then
        print_success "Enabled AGIC add-on on AKS cluster"
    else
        print_error "Failed to enable AGIC add-on. There may be an ongoing operation."
        print_info "You can check the status with: az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP"
        print_info "Or try aborting any stuck operations with: az aks operation-abort --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP"
        exit 1
    fi
fi

# Configure AGIC permissions
print_step "Configuring AGIC permissions"

# Get AGIC identity client ID
AGIC_IDENTITY_CLIENT_ID=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "addonProfiles.ingressApplicationGateway.identity.clientId" --output tsv)

if [ -n "$AGIC_IDENTITY_CLIENT_ID" ] && [ "$AGIC_IDENTITY_CLIENT_ID" != "null" ]; then
    print_info "AGIC Identity Client ID: $AGIC_IDENTITY_CLIENT_ID"
    
    # Grant Reader permission on resource group
    print_step "Granting Reader permission to AGIC on resource group"
    az role assignment create \
        --role Reader \
        --scope "/subscriptions/$(az account show --query id -o tsv)/resourceGroups/$RESOURCE_GROUP" \
        --assignee "$AGIC_IDENTITY_CLIENT_ID" \
        --only-show-errors || print_warning "Reader permission may already exist"
    
    # Grant Contributor permission on Application Gateway
    print_step "Granting Contributor permission to AGIC on Application Gateway"
    az role assignment create \
        --role Contributor \
        --scope "/subscriptions/$(az account show --query id -o tsv)/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME" \
        --assignee "$AGIC_IDENTITY_CLIENT_ID" \
        --only-show-errors || print_warning "Contributor permission may already exist"
    
    print_success "AGIC permissions configured"
else
    print_warning "Could not retrieve AGIC identity - permissions may need manual configuration"
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

# Clean up any existing resources that might conflict with Helm
print_step "Cleaning up any existing resources to prevent Helm conflicts"

# First, scale down deployments that might be using PVCs
print_info "Scaling down deployments to release PVC locks..."
kubectl scale deployment --all --replicas=0 -n $NAMESPACE &>/dev/null || true
kubectl scale statefulset --all --replicas=0 -n $NAMESPACE &>/dev/null || true

# Wait a moment for pods to terminate
print_info "Waiting for pods to terminate..."
sleep 10

# Remove any existing secrets (TLS only - OAuth2 is now managed by chart)
kubectl delete secret opik-tls-secret -n $NAMESPACE --ignore-not-found=true

# Remove any existing PVCs that might have immutable field conflicts
print_info "Cleaning up existing PersistentVolumeClaims..."

# Function to force delete PVC with finalizer removal
force_delete_pvc() {
    local pvc_name="$1"
    local namespace="$2"
    
    if kubectl get pvc "$pvc_name" -n "$namespace" &>/dev/null; then
        print_info "Deleting PVC $pvc_name..."
        
        # First, try normal deletion
        kubectl delete pvc "$pvc_name" -n "$namespace" --timeout=10s &>/dev/null || true
        
        # Check if it's stuck in Terminating state
        PVC_STATUS=$(kubectl get pvc "$pvc_name" -n "$namespace" -o jsonpath='{.status.phase}' 2>/dev/null || echo "NotFound")
        
        if [ "$PVC_STATUS" = "Terminating" ] || kubectl get pvc "$pvc_name" -n "$namespace" &>/dev/null; then
            print_warning "PVC $pvc_name is stuck - removing finalizers..."
            
            # Remove finalizers to allow deletion
            kubectl patch pvc "$pvc_name" -n "$namespace" -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
            
            # Force delete with grace period 0
            kubectl delete pvc "$pvc_name" -n "$namespace" --force --grace-period=0 &>/dev/null || true
            
            print_success "Force deleted PVC $pvc_name"
        else
            print_success "Deleted PVC $pvc_name"
        fi
    fi
}

# Delete common PVCs that cause conflicts
force_delete_pvc "opik-minio" "$NAMESPACE"
force_delete_pvc "data-opik-clickhouse-cluster-0-0-0" "$NAMESPACE"
force_delete_pvc "data-opik-mysql-0" "$NAMESPACE"

# Also delete any other ClickHouse related PVCs
print_info "Checking for additional ClickHouse PVCs..."
kubectl get pvc -n "$NAMESPACE" -o name | grep "storage-vc-template-chi" | while read pvc_path; do
    pvc_name=$(echo "$pvc_path" | sed 's|persistentvolumeclaim/||')
    force_delete_pvc "$pvc_name" "$NAMESPACE"
done

# Wait for all resources to be completely removed before proceeding
print_info "Waiting for resource deletion to complete..."
MAX_WAIT=60
WAIT_COUNT=0

# Wait for TLS secret to be deleted  
while kubectl get secret opik-tls-secret -n $NAMESPACE &>/dev/null && [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    print_info "Waiting for TLS secret to be deleted... ($WAIT_COUNT/$MAX_WAIT)"
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 2))
done

# Reset counter for PVC cleanup
WAIT_COUNT=0

# Wait for PVCs to be deleted (this is critical for avoiding immutable field errors)
while (kubectl get pvc opik-minio -n $NAMESPACE &>/dev/null || kubectl get pvc data-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE &>/dev/null || kubectl get pvc data-opik-mysql-0 -n $NAMESPACE &>/dev/null) && [ $WAIT_COUNT -lt $MAX_WAIT ]; do
    print_info "Waiting for PVCs to be deleted... ($WAIT_COUNT/$MAX_WAIT)"
    sleep 2
    WAIT_COUNT=$((WAIT_COUNT + 2))
done

# Force cleanup any remaining problematic resources
if kubectl get secret opik-tls-secret -n $NAMESPACE &>/dev/null; then
    print_error "TLS secret still exists after cleanup attempt. Forcing removal..."
    kubectl patch secret opik-tls-secret -n $NAMESPACE -p '{"metadata":{"finalizers":[]}}' --type=merge &>/dev/null || true
    kubectl delete secret opik-tls-secret -n $NAMESPACE --force --grace-period=0 &>/dev/null || true
    sleep 3
fi

# Force cleanup any remaining PVCs that could cause immutable field errors
PVC_CLEANUP_NEEDED=false
for pvc_name in "opik-minio" "data-opik-clickhouse-cluster-0-0-0" "data-opik-mysql-0"; do
    if kubectl get pvc $pvc_name -n $NAMESPACE &>/dev/null; then
        print_warning "PVC $pvc_name still exists - forcing removal to prevent immutable field errors..."
        
        # Remove finalizers first to prevent hanging
        kubectl patch pvc $pvc_name -n $NAMESPACE -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
        
        # Then force delete
        kubectl delete pvc $pvc_name -n $NAMESPACE --force --grace-period=0 &>/dev/null || true
        PVC_CLEANUP_NEEDED=true
    fi
done

# Also check for any stuck ClickHouse PVCs
kubectl get pvc -n $NAMESPACE -o name 2>/dev/null | grep "storage-vc-template-chi" | while read pvc_path; do
    pvc_name=$(echo "$pvc_path" | sed 's|persistentvolumeclaim/||')
    if kubectl get pvc "$pvc_name" -n $NAMESPACE &>/dev/null; then
        print_warning "ClickHouse PVC $pvc_name still exists - forcing removal..."
        kubectl patch pvc "$pvc_name" -n $NAMESPACE -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
        kubectl delete pvc "$pvc_name" -n $NAMESPACE --force --grace-period=0 &>/dev/null || true
        PVC_CLEANUP_NEEDED=true
    fi
done

if [ "$PVC_CLEANUP_NEEDED" = true ]; then
    print_info "Waiting additional time for PVC cleanup to complete..."
    sleep 10
fi

# Final validation
REMAINING_RESOURCES=""
if kubectl get secret opik-tls-secret -n $NAMESPACE &>/dev/null; then
    REMAINING_RESOURCES="$REMAINING_RESOURCES opik-tls-secret(secret)"
fi

for pvc_name in "opik-minio" "data-opik-clickhouse-cluster-0-0-0" "data-opik-mysql-0"; do
    if kubectl get pvc $pvc_name -n $NAMESPACE &>/dev/null; then
        REMAINING_RESOURCES="$REMAINING_RESOURCES $pvc_name(pvc)"
    fi
done

if [ -n "$REMAINING_RESOURCES" ]; then
    print_error "Cannot remove existing resources: $REMAINING_RESOURCES"
    print_error "Manual intervention required. Please delete these resources manually:"
    echo "$REMAINING_RESOURCES" | tr ' ' '\n' | while read resource; do
        if [[ $resource == *"(secret)"* ]]; then
            resource_name=$(echo $resource | sed 's/(secret)//')
            print_info "kubectl delete secret $resource_name -n $NAMESPACE --force"
        elif [[ $resource == *"(pvc)"* ]]; then
            resource_name=$(echo $resource | sed 's/(pvc)//')
            print_info "kubectl delete pvc $resource_name -n $NAMESPACE --force"
        fi
    done
    exit 1
else
    print_success "Successfully cleaned up all conflicting resources"
fi

# Substitute environment variables in helm-values-azure-template.yaml
print_step "Preparing Helm values with environment variables"

# Final validation of OAuth2 cookie secret before Helm deployment
print_step "Validating OAuth2 cookie secret before Helm deployment"
if [ -z "${OAUTH2_COOKIE_SECRET:-}" ]; then
    print_error "OAUTH2_COOKIE_SECRET is not set! This should not happen."
    exit 1
elif [ ${#OAUTH2_COOKIE_SECRET} -ne 32 ]; then
    print_error "OAUTH2_COOKIE_SECRET is ${#OAUTH2_COOKIE_SECRET} bytes, but OAuth2 proxy requires exactly 32 bytes"
    print_error "Current value: '$OAUTH2_COOKIE_SECRET'"
    print_step "Regenerating correct OAuth2 cookie secret"
    OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)
    print_success "Generated new 32-byte OAuth2 cookie secret: $OAUTH2_COOKIE_SECRET"
    export OAUTH2_COOKIE_SECRET
else
    print_success "OAuth2 cookie secret validation passed (32 bytes)"
fi

export ACR_LOGIN_SERVER
export OPIK_VERSION
export TOGGLE_GUARDRAILS_ENABLED
export DOMAIN_NAME
export PUBLIC_IP_ADDRESS
export NAMESPACE

# Create a temporary values file with substituted variables
envsubst < helm-values-azure-template.yaml > helm-values-azure-resolved.yaml

print_success "Environment variables substituted in Helm values"

# Final pre-deployment check to ensure no resource conflicts
print_step "Final pre-deployment verification"

# Check for any remaining secrets (TLS only - OAuth2 now managed by chart)
if kubectl get secret opik-tls-secret -n $NAMESPACE &>/dev/null; then
    print_error "CRITICAL: TLS secret still exists before Helm deployment. This will cause deployment failure."
    print_info "Attempting emergency cleanup..."
    kubectl delete secret opik-tls-secret -n $NAMESPACE --force --grace-period=0 &>/dev/null || true
    sleep 3
    if kubectl get secret opik-tls-secret -n $NAMESPACE &>/dev/null; then
        print_error "Emergency cleanup failed. Cannot proceed with deployment."
        print_info "Manual fix required: kubectl delete secret opik-tls-secret -n $NAMESPACE --force"
        exit 1
    fi
    print_warning "Emergency cleanup successful - proceeding with deployment"
fi

# Check for any remaining PVCs that could cause immutable field errors
CONFLICTING_PVCS=""
for pvc_name in "opik-minio" "data-opik-clickhouse-cluster-0-0-0" "data-opik-mysql-0"; do
    if kubectl get pvc $pvc_name -n $NAMESPACE &>/dev/null; then
        CONFLICTING_PVCS="$CONFLICTING_PVCS $pvc_name"
    fi
done

if [ -n "$CONFLICTING_PVCS" ]; then
    print_error "CRITICAL: Existing PVCs will cause immutable field errors:$CONFLICTING_PVCS"
    print_info "Attempting emergency PVC cleanup..."
    for pvc_name in $CONFLICTING_PVCS; do
        # Remove finalizers first to prevent hanging
        kubectl patch pvc $pvc_name -n $NAMESPACE -p '{"metadata":{"finalizers":null}}' --type=merge &>/dev/null || true
        kubectl delete pvc $pvc_name -n $NAMESPACE --force --grace-period=0 &>/dev/null || true
    done
    sleep 5
    
    # Check if cleanup was successful
    REMAINING_PVCS=""
    for pvc_name in $CONFLICTING_PVCS; do
        if kubectl get pvc $pvc_name -n $NAMESPACE &>/dev/null; then
            REMAINING_PVCS="$REMAINING_PVCS $pvc_name"
        fi
    done
    
    if [ -n "$REMAINING_PVCS" ]; then
        print_error "Emergency PVC cleanup failed. Cannot proceed with deployment."
        print_info "Manual fix required:"
        for pvc_name in $REMAINING_PVCS; do
            print_info "kubectl delete pvc $pvc_name -n $NAMESPACE --force"
        done
        exit 1
    fi
    print_warning "Emergency PVC cleanup successful - proceeding with deployment"
else
    print_success "No conflicting PVCs detected - safe to proceed"
fi

print_success "Pre-deployment verification completed - no resource conflicts detected"

# Install or upgrade Opik with forced replacement to handle any existing resources
helm upgrade --install opik ./helm_chart/opik \
    --namespace $NAMESPACE \
    --values helm-values-azure-resolved.yaml \
    --force \
    --debug \
    --timeout 5m

print_success "Helm installation initiated"

# Validate OAuth2 secret was created correctly
print_step "Validating OAuth2 secret deployment"
RETRY_COUNT=0
MAX_RETRIES=10
while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    if kubectl get secret opik-oauth2-proxy -n $NAMESPACE &>/dev/null; then
        # Check if the secret has the correct cookie-secret
        DEPLOYED_COOKIE_SECRET=$(kubectl get secret opik-oauth2-proxy -n $NAMESPACE -o jsonpath='{.data.cookie-secret}' | base64 -d 2>/dev/null || echo "")
        if [ ${#DEPLOYED_COOKIE_SECRET} -eq 32 ]; then
            print_success "OAuth2 secret validated successfully (32-byte cookie secret)"
            break
        else
            print_warning "OAuth2 secret exists but cookie-secret is wrong length: ${#DEPLOYED_COOKIE_SECRET} bytes"
        fi
    else
        print_info "Waiting for OAuth2 secret to be created... (attempt $((RETRY_COUNT + 1))/$MAX_RETRIES)"
    fi
    
    sleep 3
    RETRY_COUNT=$((RETRY_COUNT + 1))
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    print_error "OAuth2 secret validation failed after $MAX_RETRIES attempts"
    print_info "This may cause OAuth2 proxy startup issues"
fi

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

# Configure HTTPS with TLS secret for AGIC
print_step "Configuring HTTPS with TLS secret for AGIC"

# Create TLS secret for AGIC to work with Ingress TLS configuration
print_info "Creating TLS secret for AGIC HTTPS configuration..."

# Create temporary SSL certificate files for TLS secret
TEMP_CERT_DIR=$(mktemp -d)
CERT_FILE="$TEMP_CERT_DIR/tls.crt"
KEY_FILE="$TEMP_CERT_DIR/tls.key"

# Generate a self-signed certificate for the TLS secret
# AGIC will use its own SSL certificate but needs this secret to exist
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/CN=$PUBLIC_IP_ADDRESS" 2>/dev/null

# Create or update the TLS secret
kubectl create secret tls opik-tls-secret \
    --cert="$CERT_FILE" \
    --key="$KEY_FILE" \
    --namespace=$NAMESPACE \
    --dry-run=client -o yaml | kubectl apply -f -

# Clean up temporary files
rm -rf "$TEMP_CERT_DIR"

print_success "Created TLS secret for AGIC HTTPS configuration"

# Wait for AGIC to process the ingress with TLS configuration
print_step "Waiting for AGIC to configure HTTPS..."
echo "AGIC will process the Ingress TLS configuration and create HTTPS listeners..."
sleep 45

# Check ingress status
print_step "Checking ingress configuration"
kubectl get ingress -n $NAMESPACE -o wide

# Verify AGIC has created HTTPS configuration
print_step "Verifying AGIC HTTPS configuration"
HTTPS_PORT_EXISTS=$(az network application-gateway frontend-port list --gateway-name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "[?port==\`443\`].name" -o tsv 2>/dev/null || echo "")

if [ -n "$HTTPS_PORT_EXISTS" ]; then
    print_success "AGIC has successfully configured HTTPS (port 443)"
    
    # Check HTTPS listeners
    HTTPS_LISTENERS=$(az network application-gateway http-listener list --gateway-name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "[?protocol==\`Https\`].name" -o tsv 2>/dev/null | wc -l || echo "0")
    
    if [ "$HTTPS_LISTENERS" -gt 0 ]; then
        print_success "AGIC has created HTTPS listeners"
    else
        print_warning "HTTPS port exists but no HTTPS listeners found"
    fi
else
    print_warning "AGIC has not yet configured HTTPS port - this may take additional time"
    print_info "HTTPS configuration may complete in the background"
fi

# Verify OAuth2 proxy deployment and secrets
print_step "Verifying OAuth2 proxy configuration"

# Check if OAuth2 proxy pods are running
OAUTH2_PODS=$(kubectl get pods -n $NAMESPACE -l app.kubernetes.io/name=oauth2-proxy --no-headers 2>/dev/null | wc -l || echo "0")
if [ "$OAUTH2_PODS" -gt 0 ]; then
    print_success "OAuth2 proxy pods are running"
    kubectl get pods -n $NAMESPACE -l app.kubernetes.io/name=oauth2-proxy
    
    # Check OAuth2 proxy logs for any configuration issues
    print_info "Checking OAuth2 proxy logs for configuration issues..."
    kubectl logs -n $NAMESPACE -l app.kubernetes.io/name=oauth2-proxy --tail=20 || true
else
    print_warning "No OAuth2 proxy pods found - checking deployment..."
    kubectl get deployment -n $NAMESPACE | grep oauth2 || true
fi

# Verify secrets are properly set
print_step "Verifying OAuth2 secrets"
OAUTH2_SECRET_EXISTS=$(kubectl get secret -n $NAMESPACE | grep oauth2-proxy | wc -l || echo "0")
if [ "$OAUTH2_SECRET_EXISTS" -gt 0 ]; then
    print_success "OAuth2 proxy secrets exist"
    kubectl get secrets -n $NAMESPACE | grep oauth2
else
    print_warning "OAuth2 proxy secrets missing - this may cause 500 errors"
    print_info "OAuth2 secrets should be managed by Helm chart - check helm values configuration"
    
    # Check if Helm values have OAuth2 configuration
    print_info "Verifying OAuth2 configuration in Helm values..."
    if [ -f "helm-values-azure-resolved.yaml" ]; then
        grep -A 5 "oauth2-proxy:" helm-values-azure-resolved.yaml || print_warning "No OAuth2 configuration found in Helm values"
    fi
fi

# Final connectivity test
print_step "Testing HTTPS connectivity"
HTTPS_URL="https://$PUBLIC_IP_ADDRESS"
if [ -n "$DOMAIN_NAME" ]; then
    HTTPS_URL="https://$DOMAIN_NAME"
fi

print_info "Testing HTTPS access to: $HTTPS_URL"
sleep 5  # Give a moment for any final AGIC updates

HTTP_STATUS=$(curl -o /dev/null -s -w "%{http_code}" --connect-timeout 15 -k "$HTTPS_URL" 2>/dev/null || echo "000")

case "$HTTP_STATUS" in
    "302")
        print_success "✅ HTTPS is working! Redirecting to authentication (HTTP $HTTP_STATUS)"
        
        # Test if it's redirecting to Microsoft login
        REDIRECT_LOCATION=$(curl -s -I --connect-timeout 15 -k "$HTTPS_URL" 2>/dev/null | grep -i "location:" | head -1 || echo "")
        if echo "$REDIRECT_LOCATION" | grep -q "login.microsoftonline.com"; then
            print_success "✅ OAuth2 authentication is working! Redirecting to Microsoft login"
        else
            print_warning "⚠️  HTTPS works but may not be redirecting to Microsoft authentication"
            print_info "Redirect location: $REDIRECT_LOCATION"
        fi
        ;;
    "200")
        print_success "✅ HTTPS is working! (HTTP $HTTP_STATUS)"
        ;;
    "000")
        print_warning "⚠️  HTTPS connectivity failed - connection timeout or refused"
        print_info "HTTPS may still be configuring - wait 2-3 minutes and try accessing manually"
        ;;
    *)
        print_warning "⚠️  HTTPS connectivity issue (HTTP $HTTP_STATUS)"
        print_info "HTTPS may still be configuring - wait a few minutes and try accessing manually"
        ;;
esac

# Print comprehensive deployment information
print_header "🎉 OPIK DEPLOYMENT COMPLETED SUCCESSFULLY"

print_section "Infrastructure Summary"
print_key_value "Resource Group" "$RESOURCE_GROUP"
print_key_value "AKS Cluster" "$AKS_CLUSTER_NAME"
print_key_value "Application Gateway" "$APP_GATEWAY_NAME"
print_key_value "Public IP" "$PUBLIC_IP_ADDRESS"
print_key_value "Container Registry" "$ACR_NAME"
print_key_value "Namespace" "$NAMESPACE"

print_section "Authentication Configuration"
print_key_value "App Registration" "$OPIK_APP_NAME"
print_key_value "App ID" "$APP_ID"
print_key_value "Tenant ID" "$TENANT_ID"
print_key_value "Client Secret" "$CLIENT_SECRET"
print_key_value "OAuth2 Cookie Secret" "$OAUTH2_COOKIE_SECRET"
if [ -n "${OPIK_ACCESS_GROUP_ID:-}" ]; then
    print_key_value "Access Group" "$OPIK_ACCESS_GROUP_NAME"
    print_warning "⚠️  Add team members to the access group in Azure Portal!"
else
    print_info "Access allowed for all users in tenant"
fi

print_section "Access Information"
if [ -n "$DOMAIN_NAME" ]; then
    print_key_value "HTTPS URL (Recommended)" "https://$DOMAIN_NAME"
    print_key_value "HTTP URL" "http://$DOMAIN_NAME"
    print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
else
    print_key_value "HTTPS URL (Recommended)" "https://$PUBLIC_IP_ADDRESS"
    print_key_value "HTTP URL" "http://$PUBLIC_IP_ADDRESS"
fi
print_warning "⚠️  HTTPS uses self-signed certificate - browsers will show security warnings"

print_section "Available Endpoints"
print_key_value "Frontend" "/"
print_key_value "Backend API" "/v1/private/*"
print_key_value "Python Backend" "/v1/private/evaluators/*"
print_key_value "Health Check" "/health-check"

print_section "Useful Commands"
echo "Check deployment status:"
print_info "kubectl get pods -n $NAMESPACE"
print_info "kubectl get ingress -n $NAMESPACE"
echo ""
echo "View application logs:"
print_info "kubectl logs -n $NAMESPACE deployment/opik-backend"
print_info "kubectl logs -n $NAMESPACE deployment/opik-frontend"
print_info "kubectl logs -n $NAMESPACE deployment/opik-python-backend"
print_info "kubectl logs -n $NAMESPACE deployment/oauth2-proxy"
echo ""
echo "Port forward (bypass authentication):"
print_info "kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173"
echo ""
echo "Manage team access:"
print_info "az ad group member add --group '$OPIK_ACCESS_GROUP_NAME' --member-id <user-email>"
print_info "az ad group member list --group '$OPIK_ACCESS_GROUP_NAME'"
echo ""
echo "Uninstall deployment:"
print_info "helm uninstall opik -n $NAMESPACE"

print_section "Troubleshooting Commands"
echo "If authentication fails with AADSTS650056 error:"
print_info "az ad app permission list --id $APP_ID"
print_info "az ad app permission admin-consent --id $APP_ID"
echo ""
echo "If you get 'Misconfigured application' errors:"
print_info "Check App Registration in Azure Portal: https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
print_info "Ensure User.Read permission is granted and admin consented"
echo ""
echo "If Application Gateway shows 502 errors:"
print_info "kubectl get pods -l app=ingress-appgw -n kube-system"
print_info "kubectl logs -l app=ingress-appgw -n kube-system --tail=50"
print_info "kubectl get ingress -n $NAMESPACE"
echo ""
echo "Check Application Gateway backend health:"
print_info "az network application-gateway show-backend-health --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP"
echo ""
echo "Restart AGIC if needed:"
print_info "kubectl rollout restart deployment/ingress-appgw-deployment -n kube-system"
echo ""
echo "Check AGIC configuration:"
print_info "kubectl describe configmap -n kube-system | grep appgw"

print_warning "🔒 Authentication is required - all users will be redirected to Microsoft login"

print_section "Next Steps"
echo "Choose how you want to access Opik:"
echo "1. Use Application Gateway (recommended) - Access via public IP with authentication"
echo "2. Use port forwarding - Access via localhost (bypasses authentication)"
echo ""
read -p "Enter your choice (1 or 2): " -n 1 -r
echo

if [[ $REPLY == "2" ]]; then
    print_step "Starting Port Forwarding"
    print_info "Opik will be available at: http://localhost:5173"
    print_warning "Press Ctrl+C to stop port forwarding"
    kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173
else
    print_step "Application Gateway Ready"
    if [ -n "$DOMAIN_NAME" ]; then
        print_success "Application available at: https://$DOMAIN_NAME (HTTPS - Recommended)"
        print_info "Also available at: http://$DOMAIN_NAME (HTTP)"
        print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
    else
        print_success "Application available at: https://$PUBLIC_IP_ADDRESS (HTTPS - Recommended)"
        print_info "Also available at: http://$PUBLIC_IP_ADDRESS (HTTP)"
    fi
    print_warning "⚠️  HTTPS uses self-signed certificate - accept browser security warning"
    print_info "It may take a few minutes for Application Gateway to configure backend pools"
    print_info "If you get 502 errors, wait a few minutes and try again"
fi

print_header "🎉 DEPLOYMENT COMPLETED SUCCESSFULLY"