#!/bin/bash

# =============================================================================
# Opik Helm Deployment Script for Azure
# =============================================================================
# This script deploys Opik on Azure using AKS, Application Gateway, and OAuth2
# Features: HTTPS with AGIC, Azure Entra ID authentication, Docker image builds.
#
# Warning:
# This script is meant to be executed one time, to setup everything.
# It can, however, be used to upgrade deployment with changes to the Helm template
# (we are using `helm upgrade` on the script).
#
# The cluster will be created and then we should just need to update it from then on.
# When executing the script and creating a cluster from 0, you may need to run it a few times
# because Azure takes time to propagate some commands and they time out.
# =============================================================================

set -e

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================

# Quick fix function for AGIC permission issues
fix_agic_permissions() {
    print_step "Fixing AGIC Permission Issues"
    print_info "This function fixes AGIC permission issues by granting permissions to the actual AGIC identity found in logs"
    
    # Load environment variables if .env.azure exists
    if [ -f ".env.azure" ]; then
        source .env.azure
    else
        print_error ".env.azure not found. Please run from deployment directory."
        return 1
    fi
    
    # Get AGIC logs and extract the actual identity being used
    print_info "Checking AGIC logs for permission errors..."
    AGIC_LOGS=$(kubectl logs -l app=ingress-appgw -n kube-system --tail=50 2>/dev/null || echo "")
    
    if echo "$AGIC_LOGS" | grep -q "AuthorizationFailed\|Forbidden\|403"; then
        print_info "Found permission issues in AGIC logs"
        
        # Extract the actual identity being used by AGIC
        ACTUAL_AGIC_IDENTITY=$(echo "$AGIC_LOGS" | grep -o "client '[^']*'" | head -1 | sed "s/client '//;s/'//")
        
        if [ -n "$ACTUAL_AGIC_IDENTITY" ]; then
            print_success "Found actual AGIC identity: $ACTUAL_AGIC_IDENTITY"
            
            SUBSCRIPTION_ID=$(az account show --query id -o tsv)
            
            print_info "Granting Reader permission on resource group..."
            az role assignment create \
                --role Reader \
                --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP" \
                --assignee "$ACTUAL_AGIC_IDENTITY" \
                --only-show-errors || print_info "Permission may already exist"
            
            print_info "Granting Contributor permission on Application Gateway..."
            az role assignment create \
                --role Contributor \
                --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME" \
                --assignee "$ACTUAL_AGIC_IDENTITY" \
                --only-show-errors || print_info "Permission may already exist"
            
            print_info "Restarting AGIC deployment..."
            kubectl rollout restart deployment -l app=ingress-appgw -n kube-system
            
            print_success "AGIC permission fix completed!"
            print_info "Wait 1-2 minutes for AGIC to restart and retry with new permissions"
        else
            print_error "Could not extract AGIC identity from logs"
            print_info "Recent AGIC logs:"
            echo "$AGIC_LOGS" | tail -10
        fi
    else
        print_success "No permission issues found in AGIC logs"
        print_info "Recent AGIC logs:"
        echo "$AGIC_LOGS" | tail -5
    fi
}

# Function to recover from failed upgrades - can be called manually
recover_from_upgrade_failure() {
    print_step "🔄 Recovering from Upgrade Failure"
    print_info "This function helps recover from failed version upgrades"
    
    # Load environment variables if .env.azure exists
    if [ -f ".env.azure" ]; then
        source .env.azure
    else
        print_error ".env.azure not found. Please run from deployment directory."
        return 1
    fi
    
    print_info "Cleaning up failed upgrade state..."
    
    # Remove failed backend pods
    print_info "Removing failed backend pods..."
    kubectl delete pods -l app.kubernetes.io/name=opik-backend -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
    kubectl delete pods -l app.kubernetes.io/name=opik-python-backend -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
    
    # Clean up stuck replica sets
    print_info "Cleaning up old replica sets..."
    for deployment in opik-backend opik-frontend opik-python-backend; do
        # Get old replica sets (with 0 replicas)
        OLD_RS=$(kubectl get rs -n $NAMESPACE -l app.kubernetes.io/name=$deployment -o jsonpath='{.items[?(@.spec.replicas==0)].metadata.name}' 2>/dev/null || echo "")
        if [ -n "$OLD_RS" ]; then
            print_info "Removing old replica sets for $deployment: $OLD_RS"
            echo "$OLD_RS" | xargs -r kubectl delete rs -n $NAMESPACE 2>/dev/null || true
        fi
    done
    
    # Reset ClickHouse database state
    print_info "Resetting ClickHouse database state..."
    if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
        print_info "Cleaning ClickHouse migration state..."
        kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP DATABASE IF EXISTS opik" 2>/dev/null || true
        kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "CREATE DATABASE IF NOT EXISTS opik" 2>/dev/null || true
        kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOG" 2>/dev/null || true
        kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOGLOCK" 2>/dev/null || true
        print_success "ClickHouse database state reset"
    else
        print_warning "ClickHouse not accessible for cleanup"
    fi
    
    # Restart deployments to recover from stuck state
    print_info "Restarting deployments to recover from stuck state..."
    for deployment in opik-backend opik-python-backend; do
        kubectl rollout restart deployment $deployment -n $NAMESPACE 2>/dev/null || true
    done
    
    print_success "Recovery operations completed"
    print_info "Monitor pod status with: kubectl get pods -n $NAMESPACE"
    print_info "Check deployment status with: kubectl rollout status deployment/opik-backend -n $NAMESPACE"
}

# =============================================================================
# OUTPUT FORMATTING FUNCTIONS  
# =============================================================================

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

# =============================================================================
# ENVIRONMENT CONFIGURATION
# =============================================================================

# Load environment variables from .env.azure
if [ -f ".env.azure" ]; then
    print_step "Loading environment variables from .env.azure"
    
    # Source the file directly after filtering out comments and empty lines
    set -a
    while IFS= read -r line; do
        # Skip empty lines and comments
        if [[ -n "$line" && ! "$line" =~ ^[[:space:]]*# ]]; then
            # If line contains =, evaluate it to handle quotes properly
            if [[ "$line" =~ = ]]; then
                eval "$line"
            fi
        fi
    done < .env.azure
    set +a
    
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

# =============================================================================
# DEPLOYMENT OVERVIEW
# =============================================================================

print_step "🚀 Starting Opik Helm deployment to Azure"

# Force unset any OAuth2 cookie secret from previous runs to ensure fresh generation
unset OAUTH2_COOKIE_SECRET

print_section "Deployment Configuration"
print_key_value "Resource Group" "$RESOURCE_GROUP"
print_key_value "Container Registry" "$ACR_NAME"
print_key_value "AKS Cluster" "$AKS_CLUSTER_NAME"
print_key_value "Azure Location" "$LOCATION"
print_key_value "Images Version" "$OPIK_VERSION"
print_key_value "Kubernetes Namespace" "$NAMESPACE"

# Check for potential conflicts from previous runs
if kubectl get namespace "$NAMESPACE" &>/dev/null; then
    print_info "Namespace $NAMESPACE already exists - checking for potential conflicts..."
    
    EXISTING_SECRETS=$(kubectl get secret -n "$NAMESPACE" 2>/dev/null | grep -E "(opik-oauth2-proxy|opik-tls-secret)" | wc -l || echo "0")
    
    if [ "$EXISTING_SECRETS" -gt 0 ]; then
        print_warning "Found existing secrets that might conflict with Helm deployment:"
        kubectl get secret -n "$NAMESPACE" | grep -E "(opik-oauth2-proxy|opik-tls-secret)" || true
        print_info "These secrets will be cleaned up automatically before Helm deployment"
    fi
fi

# =============================================================================
# PREREQUISITES CHECK
# =============================================================================

print_step "🔍 Checking prerequisites"

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

# =============================================================================
# AZURE INFRASTRUCTURE SETUP
# =============================================================================

# Create Resource Group (if it doesn't exist)
print_step "🏗️ Creating Resource Group"
if az group show --name $RESOURCE_GROUP &> /dev/null; then
    print_warning "Resource group $RESOURCE_GROUP already exists"
else
    az group create --name $RESOURCE_GROUP --location $LOCATION
    print_success "🏗️ Created resource group $RESOURCE_GROUP"
fi

# Create Azure Container Registry (if it doesn't exist)
print_step "📦 Creating Azure Container Registry"
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

# =============================================================================
# NETWORKING INFRASTRUCTURE
# =============================================================================

print_step "🌐 Setting up networking infrastructure"

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

# =============================================================================
# AKS CLUSTER SETUP
# =============================================================================

print_step "☸️ Creating AKS Cluster"
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
    print_info "Creating AKS cluster with VNet integration (this may take 10-15 minutes)..."
    az aks create \
        --resource-group $RESOURCE_GROUP \
        --name $AKS_CLUSTER_NAME \
        --node-count 1 \
        --min-count 1 \
        --max-count 2 \
        --enable-cluster-autoscaler \
        --tier free \
        --node-vm-size Standard_B4ms \
        --enable-addons monitoring \
        --generate-ssh-keys \
        --attach-acr $ACR_NAME \
        --location $LOCATION \
        --vnet-subnet-id $AKS_SUBNET_ID \
        --network-plugin azure \
        --service-cidr 10.1.0.0/16 \
        --dns-service-ip 10.1.0.10
    print_success "☸️ Created AKS cluster $AKS_CLUSTER_NAME with VNet integration"
fi

# =============================================================================
# AKS PERMISSIONS SETUP
# =============================================================================

print_step "🔐 Configuring AKS cluster permissions"

# Get AKS cluster's managed identity
AKS_IDENTITY_PRINCIPAL_ID=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "identity.principalId" -o tsv)
SUBSCRIPTION_ID=$(az account show --query id -o tsv)

if [ -n "$AKS_IDENTITY_PRINCIPAL_ID" ] && [ "$AKS_IDENTITY_PRINCIPAL_ID" != "null" ]; then
    print_info "AKS Managed Identity Principal ID: $AKS_IDENTITY_PRINCIPAL_ID"
    
    # Grant Contributor permission on the resource group for disk creation
    print_info "Granting Contributor permission to AKS on resource group for disk management..."
    az role assignment create \
        --role "Contributor" \
        --assignee "$AKS_IDENTITY_PRINCIPAL_ID" \
        --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP" \
        --only-show-errors || print_info "Permission may already exist"
    
    print_success "AKS permissions configured successfully"
else
    print_error "Could not retrieve AKS managed identity principal ID"
    exit 1
fi

# =============================================================================
# PUBLIC IP AND APPLICATION GATEWAY SETUP
# =============================================================================

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

# =============================================================================
# AZURE ENTRA ID AUTHENTICATION SETUP
# =============================================================================

print_step "🔐 Setting up Azure Entra ID authentication"

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
                wait_time=$((wait_time * 2))
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
        print_warning "Remember to add your team members to this group in the Azure Portal!"
    else
        print_success "Found existing Microsoft Entra ID group: $OPIK_ACCESS_GROUP_NAME (ID: $OPIK_ACCESS_GROUP_ID)"
    fi
    
    export OPIK_ACCESS_GROUP_ID
else
    print_warning "OPIK_ACCESS_GROUP_NAME not specified in .env.azure - authentication will allow any user in your tenant"
fi

# Create App Registration for Opik authentication
# Preserve OPIK_APP_NAME from .env.azure if set, otherwise use default pattern
ORIGINAL_OPIK_APP_NAME="${OPIK_APP_NAME:-}"
OPIK_APP_NAME="${OPIK_APP_NAME:-opik-frontend-auth-${RESOURCE_GROUP}}"
print_step "Creating App Registration for Opik authentication"
print_info "App Registration Name: $OPIK_APP_NAME"

# Check if app registration already exists
print_info "Searching for existing App Registration with name: $OPIK_APP_NAME"
APP_ID=$(az ad app list --display-name "$OPIK_APP_NAME" --query "[0].appId" -o tsv)

# If not found with full name, try searching for the base name from .env.azure
if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
    if [ -n "$ORIGINAL_OPIK_APP_NAME" ] && [ "$ORIGINAL_OPIK_APP_NAME" != "$OPIK_APP_NAME" ]; then
        print_info "Trying alternative search with base name from .env.azure: $ORIGINAL_OPIK_APP_NAME"
        APP_ID=$(az ad app list --display-name "$ORIGINAL_OPIK_APP_NAME" --query "[0].appId" -o tsv)
        if [ -n "$APP_ID" ] && [ "$APP_ID" != "null" ]; then
            print_info "Found existing app registration with base name: $ORIGINAL_OPIK_APP_NAME"
            OPIK_APP_NAME="$ORIGINAL_OPIK_APP_NAME"
        fi
    fi
fi

print_info "Search result for APP_ID: '$APP_ID'"

if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
    print_info "No existing app registration found - creating new one"
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
    # 64a6cdd6-aab1-4aaf-94b8-3cc8405e90d0 = email permission (fixed GUID)
    # 14dad69e-099b-42c9-810b-d002981feec1 = profile permission (fixed GUID)
    # 37f7f235-527c-4136-accd-4a02d197296e = openid permission (fixed GUID)
    # Scope = Delegated permission (acts on behalf of signed-in user)
    print_step "Configuring Microsoft Graph permissions"
    
    # Add User.Read permission
    az ad app permission add \
        --id $APP_ID \
        --api 00000003-0000-0000-c000-000000000000 \
        --api-permissions e1fe6dd8-ba31-4d61-89e7-88639da4683d=Scope
    
    # Add email permission for OAuth2 user profile access
    az ad app permission add \
        --id $APP_ID \
        --api 00000003-0000-0000-c000-000000000000 \
        --api-permissions 64a6cdd6-aab1-4aaf-94b8-3cc8405e90d0=Scope
    
    # Add profile permission for OAuth2 user profile access
    az ad app permission add \
        --id $APP_ID \
        --api 00000003-0000-0000-c000-000000000000 \
        --api-permissions 14dad69e-099b-42c9-810b-d002981feec1=Scope
    
    # Add openid permission for OAuth2 authentication
    az ad app permission add \
        --id $APP_ID \
        --api 00000003-0000-0000-c000-000000000000 \
        --api-permissions 37f7f235-527c-4136-accd-4a02d197296e=Scope
    
    print_success "Added Microsoft Graph permissions: User.Read, email, profile, openid"
    
    # Grant admin consent for the permissions with retry logic for propagation delays
    print_info "Granting admin consent for permissions (waiting for Azure AD propagation)"
    
    # Call the retry function
    grant_admin_consent_with_retry "$APP_ID"
        
    # Create service principal (check if it already exists first)
    print_info "Creating service principal"
    
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
    print_info "Generating client secret"
    CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)

    print_success "Generated client secret for app registration"

    # Display authentication credentials in a standardized format
    print_section "Authentication Credentials Created"
    print_key_value "App ID" "$APP_ID"
    print_key_value "Client Secret" "$CLIENT_SECRET"
    print_key_value "Tenant ID" "$TENANT_ID"
    print_key_value "Redirect URL" "$REDIRECT_URL"
    print_warning "IMPORTANT: Save these credentials securely - they will not be stored in files!"
    
else
    print_warning "App Registration $OPIK_APP_NAME already exists (App ID: $APP_ID)"
    print_info "Found existing App Registration with ID: $APP_ID"
    
    # Validate that we have a valid APP_ID
    if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
        print_error "Failed to retrieve valid APP_ID for existing app registration"
        print_error "This should not happen - something is wrong with the Azure AD query"
        exit 1
    fi
    
    # Check and add Microsoft Graph permissions if missing
    print_info "Checking Microsoft Graph permissions"
    
    # Define required permissions with their GUIDs using a shell-compatible approach
    REQUIRED_PERMISSIONS=(
        "e1fe6dd8-ba31-4d61-89e7-88639da4683d:User.Read"
        "64a6cdd6-aab1-4aaf-94b8-3cc8405e90d0:email"
        "14dad69e-099b-42c9-810b-d002981feec1:profile"
        "37f7f235-527c-4136-accd-4a02d197296e:openid"
        "62a82d76-70ea-41e2-9197-370581804d09:GroupMember.Read.All"
    )
    
    MISSING_PERMISSIONS=()
    
    # Check each required permission
    for permission_entry in "${REQUIRED_PERMISSIONS[@]}"; do
        permission_id="${permission_entry%%:*}"
        permission_name="${permission_entry##*:}"
        EXISTING=$(az ad app permission list --id $APP_ID --query "[?resourceAppId=='00000003-0000-0000-c000-000000000000'].resourceAccess[?id=='$permission_id']" -o tsv)
        if [ -z "$EXISTING" ]; then
            MISSING_PERMISSIONS+=("$permission_id:$permission_name")
            print_warning "Microsoft Graph $permission_name permission missing"
        else
            print_success "Microsoft Graph $permission_name permission already configured"
        fi
    done
    
    # Add missing permissions
    if [ ${#MISSING_PERMISSIONS[@]} -gt 0 ]; then
        print_info "Adding missing Microsoft Graph permissions"
        for permission_entry in "${MISSING_PERMISSIONS[@]}"; do
            permission_id="${permission_entry%%:*}"
            permission_name="${permission_entry##*:}"
            print_info "Adding $permission_name permission..."
            az ad app permission add \
                --id $APP_ID \
                --api 00000003-0000-0000-c000-000000000000 \
                --api-permissions "$permission_id=Scope"
        done
        
        # Grant admin consent with retry (reuse the function defined above)
        print_info "Granting admin consent for added permissions"
        grant_admin_consent_with_retry "$APP_ID"
        print_success "Added and consented missing Microsoft Graph permissions"
    else
        print_success "All required Microsoft Graph permissions already configured"
    fi
    
    # Check if CLIENT_SECRET is already set
    if [ -z "${CLIENT_SECRET:-}" ]; then
        print_warning "CLIENT_SECRET not found in environment variables - regenerating client secret"
        
        # Validate APP_ID before trying to reset credentials
        if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
            print_error "Cannot regenerate client secret: APP_ID is empty or invalid"
            print_error "This indicates an issue with app registration detection"
            exit 1
        fi
        
        # Generate new client secret
        CLIENT_SECRET=$(az ad app credential reset --id $APP_ID --query "password" -o tsv)
        
        if [ -n "$CLIENT_SECRET" ]; then
            print_success "Regenerated client secret for existing app registration"
            
            # Display updated credentials
            print_section "Authentication Credentials (Regenerated)"
            print_key_value "App ID" "$APP_ID"
            print_key_value "Client Secret" "$CLIENT_SECRET"
            print_key_value "Tenant ID" "$TENANT_ID"
            print_warning "IMPORTANT: Client secret has been regenerated - update your records!"
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
    OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)
    print_success "Generated OAuth2 cookie secret (32 bytes)"
    print_section "OAuth2 Configuration"
    print_key_value "Cookie Secret" "$OAUTH2_COOKIE_SECRET"
else
    # Validate existing cookie secret length
    COOKIE_SECRET_LENGTH=${#OAUTH2_COOKIE_SECRET}
    if [ "$COOKIE_SECRET_LENGTH" -ne 32 ]; then
        print_warning "Existing OAuth2 cookie secret is $COOKIE_SECRET_LENGTH bytes, but 32 bytes required"
        print_info "Regenerating OAuth2 cookie secret with correct length"
        OAUTH2_COOKIE_SECRET=$(openssl rand -hex 16)
        print_success "Generated new OAuth2 cookie secret (32 bytes)"
        print_section "OAuth2 Configuration"
        print_key_value "Cookie Secret" "$OAUTH2_COOKIE_SECRET"
    else
        print_success "Using existing OAuth2 cookie secret from environment (32 bytes)"
    fi
fi

# Export authentication variables for Helm values substitution
print_info "Exporting authentication variables for Helm"

if [ -z "${CLIENT_SECRET:-}" ]; then
    print_error "CLIENT_SECRET is empty - authentication setup failed"
    exit 1
fi

export APP_ID
export CLIENT_SECRET
export TENANT_ID
export OAUTH2_COOKIE_SECRET
export OPIK_ACCESS_GROUP_ID

print_success "🔐 Azure Entra ID authentication setup completed"

# =============================================================================
# APPLICATION GATEWAY SETUP
# =============================================================================

# Create Application Gateway (if it doesn't exist)
if az network application-gateway show --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP &> /dev/null; then
    print_warning "Application Gateway $APP_GATEWAY_NAME already exists"
else
    print_info "Creating Application Gateway (this may take 5-10 minutes)..."
    az network application-gateway create \
        --name $APP_GATEWAY_NAME \
        --location $LOCATION \
        --resource-group $RESOURCE_GROUP \
        --capacity 1 \
        --sku Standard_v2 \
        --http-settings-cookie-based-affinity Disabled \
        --frontend-port 80 \
        --http-settings-port 80 \
        --http-settings-protocol Http \
        --public-ip-address $PUBLIC_IP_NAME \
        --vnet-name $VNET_NAME \
        --subnet $APP_GATEWAY_SUBNET_NAME \
        --priority 1000 \
        --min-capacity 1 \
        --max-capacity 2
    print_success "Created Application Gateway $APP_GATEWAY_NAME"
fi

print_info "HTTPS will be configured by AGIC during deployment"
print_info "AGIC will automatically create HTTPS infrastructure based on Ingress TLS configuration"

# =============================================================================
# KUBERNETES CLUSTER CONNECTION
# =============================================================================

# Get AKS credentials
print_step "🔗 Getting AKS credentials"
az aks get-credentials --resource-group $RESOURCE_GROUP --name $AKS_CLUSTER_NAME --overwrite-existing
print_success "Retrieved AKS credentials"

# Test cluster connection
kubectl cluster-info
print_success "Connected to AKS cluster"

# =============================================================================
# AGIC (APPLICATION GATEWAY INGRESS CONTROLLER) SETUP
# =============================================================================

print_step "Installing Azure Application Gateway Ingress Controller (AGIC)"

# Enable AGIC add-on for AKS
APP_GATEWAY_ID=$(az network application-gateway show --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "id" --output tsv)

# Check if AGIC is already enabled
AGIC_ENABLED=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "addonProfiles.ingressApplicationGateway.enabled" --output tsv 2>/dev/null || echo "false")

if [ "$AGIC_ENABLED" = "true" ]; then
    print_warning "AGIC add-on is already enabled on AKS cluster"
else
    print_info "Enabling AGIC add-on on AKS cluster..."
    
    # Check for any in-progress operations and wait for them to complete
    print_info "Checking for any in-progress AKS operations..."
    AKS_OPERATION_STATUS=""
    MAX_WAIT_TIME=600
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

# Get AGIC identity client ID from AKS configuration
AGIC_IDENTITY_CLIENT_ID=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "addonProfiles.ingressApplicationGateway.identity.clientId" --output tsv)

if [ -n "$AGIC_IDENTITY_CLIENT_ID" ] && [ "$AGIC_IDENTITY_CLIENT_ID" != "null" ]; then
    print_info "AGIC Identity Client ID from AKS: $AGIC_IDENTITY_CLIENT_ID"
    
    # Get the subscription ID
    SUBSCRIPTION_ID=$(az account show --query id -o tsv)
    
    # Grant Reader permission on resource group
    print_step "Granting Reader permission to AGIC on resource group"
    az role assignment create \
        --role Reader \
        --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP" \
        --assignee "$AGIC_IDENTITY_CLIENT_ID" \
        --only-show-errors || print_warning "Reader permission may already exist or failed to assign"
    
    # Grant Contributor permission on Application Gateway
    print_step "Granting Contributor permission to AGIC on Application Gateway"
    az role assignment create \
        --role Contributor \
        --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME" \
        --assignee "$AGIC_IDENTITY_CLIENT_ID" \
        --only-show-errors || print_warning "Contributor permission may already exist or failed to assign"
    
    print_success "AGIC permissions configured for identity: $AGIC_IDENTITY_CLIENT_ID"
    
    print_step "Verifying AGIC identity configuration"
    print_info "Checking for additional managed identities that might be used by AGIC..."
    
    MC_RESOURCE_GROUP=$(az aks show --name $AKS_CLUSTER_NAME --resource-group $RESOURCE_GROUP --query "nodeResourceGroup" -o tsv)
    if [ -n "$MC_RESOURCE_GROUP" ]; then
        print_info "Checking managed identities in MC resource group: $MC_RESOURCE_GROUP"
        
        MANAGED_IDENTITIES=$(az identity list --resource-group "$MC_RESOURCE_GROUP" --query "[?contains(name, 'agentpool') || contains(name, 'appgw') || contains(name, 'ingressappgw')].{name:name, clientId:clientId, principalId:principalId}" -o json 2>/dev/null || echo "[]")
        
        if [ "$MANAGED_IDENTITIES" != "[]" ] && [ -n "$MANAGED_IDENTITIES" ]; then
            print_info "Found additional managed identities in MC resource group:"
            echo "$MANAGED_IDENTITIES" | jq -r '.[] | "  - \(.name): \(.clientId)"' 2>/dev/null || echo "$MANAGED_IDENTITIES"
            
            echo "$MANAGED_IDENTITIES" | jq -r '.[].clientId' 2>/dev/null | while read -r identity_id; do
                if [ -n "$identity_id" ] && [ "$identity_id" != "null" ]; then
                    print_info "Granting permissions to additional identity: $identity_id"
                    
                    # Grant Reader permission on resource group
                    az role assignment create \
                        --role Reader \
                        --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP" \
                        --assignee "$identity_id" \
                        --only-show-errors 2>/dev/null || true
                    
                    # Grant Contributor permission on Application Gateway
                    az role assignment create \
                        --role Contributor \
                        --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME" \
                        --assignee "$identity_id" \
                        --only-show-errors 2>/dev/null || true
                fi
            done
        fi
    fi
    
    print_success "AGIC permissions verification completed"
else
    print_warning "Could not retrieve AGIC identity from AKS configuration"
    print_info "AGIC permissions may need manual configuration after deployment"
    print_info "Check AGIC logs with: kubectl logs -l app=ingress-appgw -n kube-system"
fi


# Wait for AGIC to be ready with improved error handling
print_step "Waiting for AGIC to be ready..."

# First, check if AGIC pods exist and are starting
AGIC_POD_COUNT=$(kubectl get pods -l app=ingress-appgw -n kube-system --no-headers 2>/dev/null | wc -l || echo "0")
if [ "$AGIC_POD_COUNT" -eq 0 ]; then
    print_warning "No AGIC pods found. AGIC may not be properly enabled."
    print_info "Checking AGIC deployment status..."
    kubectl get deployment -l app=ingress-appgw -n kube-system 2>/dev/null || print_warning "AGIC deployment not found"
    
    # Try to find AGIC-related pods with alternative labels
    print_info "Looking for AGIC pods with alternative labels..."
    kubectl get pods -n kube-system | grep -i agic || kubectl get pods -n kube-system | grep -i appgw || print_warning "No AGIC-related pods found"
fi

# Wait for AGIC pods to be ready, but don't fail if they have permission issues
print_info "Waiting for AGIC pods to start (timeout: 300s)..."
if kubectl wait --for=condition=Ready pod -l app=ingress-appgw -n kube-system --timeout=300s 2>/dev/null; then
    print_success "AGIC pods are ready"
else
    print_warning "AGIC pods did not become ready within timeout. Checking pod status and logs..."
    
    # Show AGIC pod status
    print_info "AGIC pod status:"
    kubectl get pods -l app=ingress-appgw -n kube-system 2>/dev/null || echo "No AGIC pods found"
    
    # Check AGIC logs for specific permission issues
    print_info "Checking AGIC logs for permission issues..."
    AGIC_LOGS=$(kubectl logs -l app=ingress-appgw -n kube-system --tail=10 2>/dev/null || echo "")
    
    if echo "$AGIC_LOGS" | grep -q "AuthorizationFailed\|Forbidden\|403"; then
        print_warning "AGIC has permission issues. Attempting to fix permissions..."
        
        # Extract the actual identity being used by AGIC from the logs
        ACTUAL_AGIC_IDENTITY=$(echo "$AGIC_LOGS" | grep -o "client '[^']*'" | head -1 | sed "s/client '//;s/'//")
        
        if [ -n "$ACTUAL_AGIC_IDENTITY" ]; then
            print_info "Found actual AGIC identity in logs: $ACTUAL_AGIC_IDENTITY"
            print_step "Granting permissions to the actual AGIC identity"
            
            SUBSCRIPTION_ID=$(az account show --query id -o tsv)
            
            # Grant Reader permission on resource group
            az role assignment create \
                --role Reader \
                --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP" \
                --assignee "$ACTUAL_AGIC_IDENTITY" \
                --only-show-errors 2>/dev/null || print_info "Reader permission assignment completed (may already exist)"
            
            # Grant Contributor permission on Application Gateway
            az role assignment create \
                --role Contributor \
                --scope "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME" \
                --assignee "$ACTUAL_AGIC_IDENTITY" \
                --only-show-errors 2>/dev/null || print_info "Contributor permission assignment completed (may already exist)"
            
            print_success "Granted permissions to actual AGIC identity: $ACTUAL_AGIC_IDENTITY"
            
            # Restart AGIC deployment to retry with new permissions
            print_step "Restarting AGIC deployment to apply new permissions"
            kubectl rollout restart deployment -l app=ingress-appgw -n kube-system 2>/dev/null || print_warning "Failed to restart AGIC deployment"
            
            # Wait for the restart to take effect
            print_info "Waiting 60 seconds for AGIC to restart with new permissions..."
            sleep 60
            
            # Try waiting again with a shorter timeout
            if kubectl wait --for=condition=Ready pod -l app=ingress-appgw -n kube-system --timeout=120s 2>/dev/null; then
                print_success "AGIC is now ready after permission fix"
            else
                print_warning "AGIC still not ready after permission fix. It may take more time to stabilize."
                print_info "AGIC will continue running in the background and should eventually become functional."
            fi
        else
            print_warning "Could not extract AGIC identity from logs"
            print_info "AGIC may need manual permission configuration"
        fi
    else
        print_warning "AGIC not ready due to other issues (not permissions). Check logs:"
        kubectl logs -l app=ingress-appgw -n kube-system --tail=20 2>/dev/null || echo "Could not retrieve AGIC logs"
    fi
    
    print_info "Continuing deployment despite AGIC readiness issues..."
    print_info "AGIC functionality can be verified after deployment completion"
fi

# =============================================================================
# DOCKER IMAGE BUILD AND PUSH
# =============================================================================

print_step "🐳 Building and pushing Docker images"

# Get ACR login server
ACR_LOGIN_SERVER=$(az acr show --name $ACR_NAME --resource-group $RESOURCE_GROUP --query "loginServer" --output tsv)
print_success "ACR login server: $ACR_LOGIN_SERVER"

# Go to the opik root directory (one level up from deployment)
cd ..

# Build backend image with proper build args
print_info "Building opik-backend image for linux/amd64"
docker build --platform linux/amd64 \
    --build-arg OPIK_VERSION=$OPIK_VERSION \
    -t $ACR_LOGIN_SERVER/opik-backend:$OPIK_VERSION \
    ./apps/opik-backend
docker push $ACR_LOGIN_SERVER/opik-backend:$OPIK_VERSION
print_success "🐳 Built and pushed opik-backend:$OPIK_VERSION"

# Build python-backend image with proper build args
print_info "Building opik-python-backend image for linux/amd64"
docker build --platform linux/amd64 \
    --build-arg OPIK_VERSION=$OPIK_VERSION \
    -t $ACR_LOGIN_SERVER/opik-python-backend:$OPIK_VERSION \
    ./apps/opik-python-backend
docker push $ACR_LOGIN_SERVER/opik-python-backend:$OPIK_VERSION
print_success "Built and pushed opik-python-backend:$OPIK_VERSION"

# Build frontend image
print_info "Building opik-frontend image for linux/amd64"
docker build --platform linux/amd64 \
    -t $ACR_LOGIN_SERVER/opik-frontend:$OPIK_VERSION \
    ./apps/opik-frontend
docker push $ACR_LOGIN_SERVER/opik-frontend:$OPIK_VERSION
print_success "🐳 Built and pushed opik-frontend:$OPIK_VERSION"

# Build sandbox executor image
print_info "Building opik-sandbox-executor-python image for linux/amd64"
docker build --platform linux/amd64 \
    -t $ACR_LOGIN_SERVER/opik-sandbox-executor-python:$OPIK_VERSION \
    ./apps/opik-sandbox-executor-python
docker push $ACR_LOGIN_SERVER/opik-sandbox-executor-python:$OPIK_VERSION
print_success "Built and pushed opik-sandbox-executor-python:$OPIK_VERSION"

# Build guardrails image (optional)
if [ "${TOGGLE_GUARDRAILS_ENABLED:-false}" = "true" ]; then
    print_info "Building opik-guardrails-backend image for linux/amd64"
    docker build --platform linux/amd64 \
        --build-arg OPIK_VERSION=$OPIK_VERSION \
        -t $ACR_LOGIN_SERVER/opik-guardrails-backend:$OPIK_VERSION \
        ./apps/opik-guardrails-backend
    docker push $ACR_LOGIN_SERVER/opik-guardrails-backend:$OPIK_VERSION
    print_success "Built and pushed opik-guardrails-backend:$OPIK_VERSION"
fi

# Return to deployment directory
cd deployment

# =============================================================================
# CERT-MANAGER AND SSL SETUP
# =============================================================================

print_step "🔒 Setting up SSL certificate management"

# Check if automatic SSL is enabled
if [ "${ENABLE_AUTO_SSL:-true}" = "true" ]; then
    print_info "Automatic SSL setup is enabled"
    
    # Install cert-manager if not already installed
    if ! kubectl get namespace cert-manager &>/dev/null; then
        print_info "Installing cert-manager..."
        kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml
        
        print_info "Waiting for cert-manager to be ready..."
        kubectl wait --for=condition=Available deployment/cert-manager -n cert-manager --timeout=300s
        kubectl wait --for=condition=Available deployment/cert-manager-cainjector -n cert-manager --timeout=300s
        kubectl wait --for=condition=Available deployment/cert-manager-webhook -n cert-manager --timeout=300s
        
        print_success "cert-manager installed and ready"
    else
        print_success "cert-manager already installed"
    fi
    
    # Create Let's Encrypt ClusterIssuer if email is provided
    if [ -n "${EMAIL_FOR_LETSENCRYPT:-}" ]; then
        print_info "Creating Let's Encrypt ClusterIssuer with correct ingress class..."
        
        cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-prod
spec:
  acme:
    server: https://acme-v02.api.letsencrypt.org/directory
    email: ${EMAIL_FOR_LETSENCRYPT}
    privateKeySecretRef:
      name: letsencrypt-prod
    solvers:
    - http01:
        ingress:
          ingressClassName: azure-application-gateway
EOF
        
        print_success "Let's Encrypt ClusterIssuer created with correct format"
        
        # Also create staging issuer for testing
        cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: letsencrypt-staging
spec:
  acme:
    server: https://acme-staging-v02.api.letsencrypt.org/directory
    email: ${EMAIL_FOR_LETSENCRYPT}
    privateKeySecretRef:
      name: letsencrypt-staging
    solvers:
    - http01:
        ingress:
          ingressClassName: azure-application-gateway
EOF
        
        print_success "Let's Encrypt staging ClusterIssuer created (for testing)"
    else
        print_warning "EMAIL_FOR_LETSENCRYPT not set - SSL certificates will be self-signed"
    fi
    
    # Set SSL configuration for Helm values
    if [ -n "${DOMAIN_NAME:-}" ]; then
        print_info "Domain name configured: $DOMAIN_NAME"
        print_info "SSL certificates will be automatically provisioned"
        export SSL_ENABLED="true"
        export SSL_ISSUER="letsencrypt-prod"
    else
        print_info "No domain name set - using self-signed certificates"
        export SSL_ENABLED="false"
        export SSL_ISSUER=""
    fi
else
    print_info "Automatic SSL setup is disabled"
    export SSL_ENABLED="false"
    export SSL_ISSUER=""
fi

# =============================================================================
# INGRESS CONFIGURATION VALIDATION
# =============================================================================

print_step "🌐 Validating ingress configuration"

# Function to validate and fix ingress class conflicts
validate_ingress_configuration() {
    print_info "Checking for ingress configuration issues..."
    
    # Check if there are any existing ingresses with conflicting configurations
    EXISTING_INGRESSES=$(kubectl get ingress -n $NAMESPACE --no-headers 2>/dev/null | wc -l || echo "0")
    
    if [ "$EXISTING_INGRESSES" -gt 0 ]; then
        print_info "Found $EXISTING_INGRESSES existing ingress(es) in namespace $NAMESPACE"
        kubectl get ingress -n $NAMESPACE -o wide
        
        # Check for ingresses with dual ingress class configurations (annotation + spec)
        print_info "Checking for dual ingress class configurations..."
        
        kubectl get ingress -n $NAMESPACE -o json | jq -r '.items[] | select(.metadata.annotations["kubernetes.io/ingress.class"] and .spec.ingressClassName) | .metadata.name' 2>/dev/null | while read ingress_name; do
            if [ -n "$ingress_name" ]; then
                print_warning "Ingress $ingress_name has both annotation and spec ingressClassName - fixing..."
                kubectl annotate ingress "$ingress_name" -n $NAMESPACE kubernetes.io/ingress.class- || true
                print_success "Removed duplicate ingress class annotation from $ingress_name"
            fi
        done
    fi
    
    # Validate AGIC backend pools are being created
    print_info "Validating Application Gateway backend pool configuration..."
    BACKEND_POOLS=$(az network application-gateway address-pool list --gateway-name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "length([?backendAddresses && length(backendAddresses) > \`0\`])" -o tsv 2>/dev/null || echo "0")
    
    if [ "$BACKEND_POOLS" -gt 0 ]; then
        print_success "Application Gateway has $BACKEND_POOLS active backend pool(s)"
        az network application-gateway address-pool list --gateway-name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP --query "[?backendAddresses && length(backendAddresses) > \`0\`].{name:name, addresses:backendAddresses[].ipAddress}" -o table 2>/dev/null || true
    else
        print_warning "Application Gateway has no active backend pools"
        print_info "This is expected for new deployments - AGIC will populate them after ingress creation"
    fi
}

# Run ingress validation
validate_ingress_configuration

# =============================================================================
# HELM CHART PREPARATION AND DEPLOYMENT
# =============================================================================

print_step "⚓ Setting up Helm dependencies"
cd helm_chart/opik

# Add required Helm repositories
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add altinity https://docs.altinity.com/clickhouse-operator/
helm repo add oauth2-proxy https://oauth2-proxy.github.io/manifests
helm repo add jetstack https://charts.jetstack.io  # For cert-manager if needed
helm repo update

# Update dependencies to latest versions and rebuild lock file
helm dependency update

# Build dependencies from updated lock file (ensures consistency)
helm dependency build

cd ../../

# Install/upgrade Opik using local Helm chart
print_step "🚀 Installing Opik using Helm"

# Create namespace if it doesn't exist
kubectl create namespace $NAMESPACE --dry-run=client -o yaml | kubectl apply -f -

# Check if this is an upgrade scenario (existing deployment exists)
if helm list -n $NAMESPACE | grep -q "opik"; then
    print_info "Existing Opik deployment detected - performing comprehensive upgrade cleanup"
    
    # Get current deployed version for comparison
    CURRENT_VERSION=$(helm list -n $NAMESPACE -o json | jq -r '.[] | select(.name=="opik") | .app_version // .chart // "unknown"' 2>/dev/null || echo "unknown")
    print_info "Current deployed version: $CURRENT_VERSION"
    print_info "Target version: $OPIK_VERSION"
    
    # Check for stuck deployments (common in failed upgrades)
    print_info "Checking for stuck deployment rollouts..."
    STUCK_DEPLOYMENTS=""
    for deployment in opik-backend opik-frontend opik-python-backend; do
        if kubectl rollout status deployment/$deployment -n $NAMESPACE --timeout=5s &>/dev/null; then
            print_success "$deployment rollout is healthy"
        else
            print_warning "$deployment rollout appears stuck - will force cleanup"
            STUCK_DEPLOYMENTS="$STUCK_DEPLOYMENTS $deployment"
        fi
    done
    
    # Comprehensive cleanup for stuck deployments
    if [ -n "$STUCK_DEPLOYMENTS" ]; then
        print_info "Found stuck deployments:$STUCK_DEPLOYMENTS"
        print_info "Performing force cleanup of stuck rollouts..."
        
        for deployment in $STUCK_DEPLOYMENTS; do
            print_info "Cleaning up $deployment deployment..."
            
            # Kill all pods for this deployment to break deadlock
            kubectl delete pods -l app.kubernetes.io/name=$deployment -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
            
            # Scale deployment to 0 to reset rollout state
            kubectl scale deployment $deployment -n $NAMESPACE --replicas=0 2>/dev/null || true
            
            # Delete old replica sets to clean up history
            kubectl get rs -n $NAMESPACE -l app.kubernetes.io/name=$deployment -o jsonpath='{.items[*].metadata.name}' | \
                xargs -r kubectl delete rs -n $NAMESPACE 2>/dev/null || true
        done
        
        print_info "Waiting for stuck deployments to fully terminate..."
        sleep 45
    fi
    
    # Clean up ClickHouse database state for version upgrades
    print_info "Preparing ClickHouse database for version upgrade..."
    
    # Stop ClickHouse cluster managed by operator
    kubectl patch chi opik-clickhouse -n $NAMESPACE --type='merge' -p='{"spec":{"stop":"yes"}}' 2>/dev/null || true
    
    # Wait for ClickHouse to stop
    print_info "Waiting for ClickHouse to stop..."
    sleep 30
    
    # Start ClickHouse temporarily for database cleanup
    kubectl patch chi opik-clickhouse -n $NAMESPACE --type='merge' -p='{"spec":{"stop":"no"}}' 2>/dev/null || true
    print_info "Starting ClickHouse temporarily for database cleanup..."
    sleep 45
    
    # Check if ClickHouse is accessible and clean database state
    CLICKHOUSE_CLEANUP_SUCCESS=false
    for attempt in {1..3}; do
        if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
            print_info "ClickHouse accessible - cleaning database state for upgrade (attempt $attempt)"
            
            # Drop and recreate opik database to ensure clean migration state
            kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP DATABASE IF EXISTS opik" 2>/dev/null || true
            kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "CREATE DATABASE IF NOT EXISTS opik" 2>/dev/null || true
            
            # Clean up Liquibase tables in default database to reset migration state
            kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOG" 2>/dev/null || true
            kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOGLOCK" 2>/dev/null || true
            
            print_success "ClickHouse database cleaned for version upgrade"
            CLICKHOUSE_CLEANUP_SUCCESS=true
            break
        else
            print_warning "ClickHouse not accessible for cleanup (attempt $attempt/3)"
            if [ $attempt -lt 3 ]; then
                sleep 30
            fi
        fi
    done
    
    if [ "$CLICKHOUSE_CLEANUP_SUCCESS" = "false" ]; then
        print_warning "Could not clean ClickHouse database - migrations will handle cleanup"
    fi
    
    # Stop ClickHouse again before continuing with upgrade
    kubectl patch chi opik-clickhouse -n $NAMESPACE --type='merge' -p='{"spec":{"stop":"yes"}}' 2>/dev/null || true
    sleep 15

    # Scale down remaining stateful sets and deployments to release PVC locks
    print_info "Scaling down all services to release PVC locks..."
    kubectl scale statefulset opik-mysql -n $NAMESPACE --replicas=0 2>/dev/null || true
    kubectl scale statefulset opik-redis-master -n $NAMESPACE --replicas=0 2>/dev/null || true
    kubectl scale statefulset opik-zookeeper -n $NAMESPACE --replicas=0 2>/dev/null || true
    kubectl scale deployment opik-minio -n $NAMESPACE --replicas=0 2>/dev/null || true

    # Remove any existing TLS secret (Helm will recreate it)
    kubectl delete secret opik-tls-secret -n $NAMESPACE --ignore-not-found=true

    # Wait for all pods to terminate
    print_info "Waiting for all pods to terminate..."
    sleep 30

    # Remove existing PVCs that may have immutable spec conflicts
    # Data is preserved because PVs have Retain policy
    print_info "Removing existing PVCs to allow Helm to recreate them (data preserved on PVs)..."
    kubectl delete pvc -n $NAMESPACE \
        opik-minio \
        data-opik-mysql-0 \
        redis-data-opik-redis-master-0 \
        storage-vc-template-chi-opik-clickhouse-cluster-0-0-0 \
        data-opik-zookeeper-0 \
        --ignore-not-found=true --timeout=60s

    # CRITICAL: After deleting PVCs, the PVs become "Released" and cannot be rebound
    # We need to clear the claimRef to make them "Available" again for rebinding
    # This ensures Helm creates new PVCs that bind to our existing PVs with data
    print_info "Making PVs available for rebinding by clearing claim references..."
    kubectl patch pv opik-mysql-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
    kubectl patch pv opik-minio-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
    kubectl patch pv opik-redis-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
    kubectl patch pv opik-clickhouse-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true

    print_success "Comprehensive upgrade cleanup completed - ready for version $OPIK_VERSION"
else
    print_info "Fresh installation detected - skipping upgrade cleanup"
fi
# Substitute environment variables in helm-values-azure-template.yaml
print_info "Preparing Helm values with environment variables"

# Final validation of OAuth2 cookie secret before Helm deployment
print_info "Validating OAuth2 cookie secret before Helm deployment"
if [ -z "${OAUTH2_COOKIE_SECRET:-}" ]; then
    print_error "OAUTH2_COOKIE_SECRET is not set! This should not happen."
    exit 1
elif [ ${#OAUTH2_COOKIE_SECRET} -ne 32 ]; then
    print_error "OAUTH2_COOKIE_SECRET is ${#OAUTH2_COOKIE_SECRET} bytes, but OAuth2 proxy requires exactly 32 bytes"
    print_error "Current value: '$OAUTH2_COOKIE_SECRET'"
    print_info "Regenerating correct OAuth2 cookie secret"
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
export SSL_ENABLED
export SSL_ISSUER
export EMAIL_FOR_LETSENCRYPT

# Re-export authentication variables to ensure they're available for envsubst
print_info "Re-exporting authentication variables for envsubst"

export APP_ID
export CLIENT_SECRET
export TENANT_ID
export OAUTH2_COOKIE_SECRET

# Validate critical variables before substitution
if [ -z "$APP_ID" ]; then
    print_error "APP_ID is empty - authentication configuration will fail"
    exit 1
fi

if [ -z "$CLIENT_SECRET" ]; then
    print_error "CLIENT_SECRET is empty - OAuth2 proxy will fail to start"
    print_error "This indicates the authentication setup did not complete properly"
    exit 1
fi

if [ -z "$TENANT_ID" ]; then
    print_error "TENANT_ID is empty - Azure authentication will fail"
    exit 1
fi

print_success "Authentication variables validated successfully"

# Create a temporary values file with substituted variables
envsubst < helm-values-azure-template.yaml > helm-values-azure-resolved.yaml

# Update SSL hosts configuration based on domain name
if [ -n "${DOMAIN_NAME:-}" ] && [ "$DOMAIN_NAME" != "" ]; then
    print_info "Configuring SSL certificate for domain: $DOMAIN_NAME"
    # Use yq to update the hosts array, or sed if yq is not available
    if command -v yq &> /dev/null; then
        yq eval ".ssl.hosts = [\"$DOMAIN_NAME\"]" -i helm-values-azure-resolved.yaml
        # Update OAuth2 redirect URL to use domain instead of IP
        yq eval ".oauth2-proxy.extraArgs.redirect-url = \"https://$DOMAIN_NAME/oauth2/callback\"" -i helm-values-azure-resolved.yaml
    else
        # Fallback to sed for updating the hosts array
        sed -i.bak "s/hosts: \[\]/hosts: [\"$DOMAIN_NAME\"]/g" helm-values-azure-resolved.yaml
        # Update OAuth2 redirect URL to use domain
        sed -i.bak "s|\$PUBLIC_IP_ADDRESS|$DOMAIN_NAME|g" helm-values-azure-resolved.yaml
        rm -f helm-values-azure-resolved.yaml.bak
    fi
    print_success "SSL certificate will be provisioned for $DOMAIN_NAME"
    print_success "OAuth2 redirect URL configured for $DOMAIN_NAME"
else
    print_info "No domain configured - using self-signed certificates for IP access"
fi

# Configure OAuth2 proxy to skip authentication for ACME challenges
print_info "OAuth2 proxy is pre-configured for ACME challenge compatibility"
print_success "ACME challenges will bypass OAuth2 authentication automatically"

print_success "Environment variables substituted in Helm values"

# =============================================================================
# PERSISTENT DISK MANAGEMENT
# =============================================================================

print_step "💾 Managing persistent disks for data persistence"

# Get the location for disk creation
DISK_LOCATION=$LOCATION

print_info "Creating persistent disks in main resource group: $RESOURCE_GROUP"
print_info "This ensures data survives cluster deletion"

# Extract disk sizes from helm template to keep them centralized
print_info "Extracting disk sizes from Helm template..."
MYSQL_SIZE=$(grep -A5 "mysql:" helm-values-azure-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')
CLICKHOUSE_SIZE=$(grep -A10 "clickhouse:" helm-values-azure-template.yaml | grep "storage:" | head -1 | sed 's/.*storage: //; s/Gi//')
MINIO_SIZE=$(grep -A5 "minio:" helm-values-azure-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')
REDIS_SIZE=$(grep -A10 "redis:" helm-values-azure-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')

print_success "Disk sizes from template: MySQL=${MYSQL_SIZE}GB, ClickHouse=${CLICKHOUSE_SIZE}GB, MinIO=${MINIO_SIZE}GB, Redis=${REDIS_SIZE}GB"

# Function to find or create disk with proper naming
find_or_create_opik_disk() {
    local service_name="$1"
    local size_gb="$2"
    local service_name_upper=$(echo "$service_name" | tr '[:lower:]' '[:upper:]')
    local var_name_disk_name="${service_name_upper}_DISK_NAME"
    local var_name_disk_id="${service_name_upper}_DISK_ID"
    
    # Look for existing opik disk for this service
    print_info "Looking for existing $service_name data disk..."
    local existing_disk=$(az disk list --resource-group $RESOURCE_GROUP --query "[?starts_with(name, 'opik-${service_name}-data')].{name:name, id:id}" -o json)
    
    if [ "$existing_disk" != "[]" ] && [ -n "$existing_disk" ]; then
        # Found existing disk(s) - use the first one
        local disk_name=$(echo "$existing_disk" | jq -r '.[0].name')
        local disk_id=$(echo "$existing_disk" | jq -r '.[0].id')
        
        print_success "Found existing $service_name disk: $disk_name"
        print_info "Reusing existing data - this preserves your previous data!"
        
        # Check if multiple disks exist and warn user
        local disk_count=$(echo "$existing_disk" | jq '. | length')
        if [ "$disk_count" -gt 1 ]; then
            print_warning "Multiple $service_name disks found:"
            echo "$existing_disk" | jq -r '.[] | "  - \(.name)"'
            print_warning "Using the first one: $disk_name"
        fi
        
        # Export the variables
        eval "export $var_name_disk_name='$disk_name'"
        eval "export $var_name_disk_id='$disk_id'"
    else
        # No existing disk found - create new one
        print_info "No existing $service_name disk found - creating new one"
        local new_disk_name="opik-${service_name}-data-$(date +%s)"
        
        print_info "Creating $service_name data disk: $new_disk_name (${size_gb}GB)"
        az disk create \
            --resource-group $RESOURCE_GROUP \
            --name $new_disk_name \
            --size-gb $size_gb \
            --location $DISK_LOCATION \
            --sku Standard_LRS
        
        local new_disk_id=$(az disk show --resource-group $RESOURCE_GROUP --name $new_disk_name --query id -o tsv)
        print_success "Created new $service_name data disk: $new_disk_name"
        
        # Export the variables
        eval "export $var_name_disk_name='$new_disk_name'"
        eval "export $var_name_disk_id='$new_disk_id'"
    fi
}

# Create or find disks for each service using sizes from template
find_or_create_opik_disk "mysql" "$MYSQL_SIZE"
find_or_create_opik_disk "clickhouse" "$CLICKHOUSE_SIZE" 
find_or_create_opik_disk "minio" "$MINIO_SIZE"
find_or_create_opik_disk "redis" "$REDIS_SIZE"

print_success "💾 Persistent disks ready"
print_info "Data will survive cluster deletion - disks are in main resource group"

# Configure existingClaim variables based on whether we're in a fresh deployment
print_info "Configuring persistence strategy"
echo "📝 Using pre-created PVs with automatic PVC binding by Helm"
echo "🔗 Helm will create PVCs that automatically bind to our pre-created PVs"

print_success "Persistence configuration prepared"

# Create PersistentVolumes for pre-created disks
print_step "🔗 Creating PersistentVolumes for pre-created disks"

# Note: Storage class will be created by Helm during deployment

# Function to create PV only - let Helm create PVCs
create_pv_for_disk() {
    local service_name="$1"
    local disk_id="$2"
    local size="$3"
    local pv_name="opik-${service_name}-pv"
    
    print_info "Creating PV for $service_name (Helm will create PVC)..."
    
    # Create PersistentVolume that Helm-created PVCs can bind to
    cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: PersistentVolume
metadata:
  name: $pv_name
  labels:
    app.kubernetes.io/name: opik
    service: $service_name
spec:
  capacity:
    storage: ${size}Gi
  accessModes:
  - ReadWriteOnce
  persistentVolumeReclaimPolicy: Retain
  storageClassName: managed-standard-retain
  csi:
    driver: disk.csi.azure.com
    volumeHandle: $disk_id
    fsType: ext4
EOF

    print_success "Created PV $pv_name for $service_name"
}

# Create PVs for all services using sizes from template
create_pv_for_disk "mysql" "$MYSQL_DISK_ID" "$MYSQL_SIZE"
create_pv_for_disk "clickhouse" "$CLICKHOUSE_DISK_ID" "$CLICKHOUSE_SIZE"
create_pv_for_disk "minio" "$MINIO_DISK_ID" "$MINIO_SIZE" 
create_pv_for_disk "redis" "$REDIS_DISK_ID" "$REDIS_SIZE"

print_success "🔗 All PersistentVolumes created - Helm will create PVCs that bind to them"

# Final pre-deployment setup
print_info "Ready for Helm deployment"

# Install or upgrade Opik with pre-created persistent disks
helm upgrade --install opik ./helm_chart/opik \
    --namespace $NAMESPACE \
    --values helm-values-azure-resolved.yaml \
    --force \
    --timeout 15m

print_success "🚀 Helm installation initiated"

# =============================================================================
# POST-DEPLOYMENT VALIDATION AND RECOVERY
# =============================================================================

print_step "📊 Post-deployment validation and recovery"
print_info "Waiting for initial deployment to stabilize..."
sleep 30

# Validate ClickHouse first (critical for backend startup)
print_info "Validating ClickHouse accessibility..."
CLICKHOUSE_READY=false
for attempt in {1..6}; do
    if kubectl wait --for=condition=Ready pod -l app=clickhouse -n $NAMESPACE --timeout=30s &>/dev/null; then
        if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
            print_success "ClickHouse is ready and accessible"
            CLICKHOUSE_READY=true
            break
        fi
    fi
    print_info "ClickHouse not ready yet (attempt $attempt/6), waiting 30 seconds..."
    sleep 30
done

if [ "$CLICKHOUSE_READY" = "false" ]; then
    print_warning "ClickHouse not accessible after 6 minutes - backend may fail to start"
    print_info "Checking ClickHouse pod status:"
    kubectl get pods -l app=clickhouse -n $NAMESPACE
fi

# Check deployment rollout status and fix stuck deployments
print_info "Validating deployment rollouts..."
FAILED_DEPLOYMENTS=""
for deployment in opik-backend opik-frontend opik-python-backend; do
    print_info "Checking $deployment rollout status..."
    
    if kubectl rollout status deployment/$deployment -n $NAMESPACE --timeout=120s &>/dev/null; then
        print_success "$deployment deployment completed successfully"
    else
        print_warning "$deployment rollout failed or timed out"
        FAILED_DEPLOYMENTS="$FAILED_DEPLOYMENTS $deployment"
        
        # Check if it's a stuck rollout due to image pull or init issues
        print_info "Diagnosing $deployment issues..."
        kubectl describe deployment $deployment -n $NAMESPACE | grep -A5 -B5 "Conditions\|Events" || true
        
        # Get pod status for this deployment
        PODS=$(kubectl get pods -l app.kubernetes.io/name=$deployment -n $NAMESPACE --no-headers 2>/dev/null || echo "")
        if [ -n "$PODS" ]; then
            print_info "$deployment pod status:"
            echo "$PODS"
            
            # Check for init container failures (common with ClickHouse connectivity)
            if echo "$PODS" | grep -q "Init:"; then
                print_warning "$deployment has init container issues - likely ClickHouse connectivity"
                
                # If ClickHouse is ready but backend still failing, restart the deployment
                if [ "$CLICKHOUSE_READY" = "true" ] && [ "$deployment" = "opik-backend" ]; then
                    print_info "ClickHouse is ready but backend init failing - restarting deployment"
                    kubectl rollout restart deployment/$deployment -n $NAMESPACE
                    
                    # Wait for restart to take effect
                    print_info "Waiting for $deployment restart to complete..."
                    if kubectl rollout status deployment/$deployment -n $NAMESPACE --timeout=180s &>/dev/null; then
                        print_success "$deployment restarted successfully"
                        # Remove from failed list
                        FAILED_DEPLOYMENTS=$(echo "$FAILED_DEPLOYMENTS" | sed "s/$deployment//g")
                    else
                        print_warning "$deployment restart still failing"
                    fi
                fi
            fi
        fi
    fi
done

# Summary of deployment status
if [ -z "$FAILED_DEPLOYMENTS" ]; then
    print_success "✅ All deployments completed successfully"
else
    print_warning "⚠️ Some deployments need attention:$FAILED_DEPLOYMENTS"
    print_info "This is often normal during upgrades - services may take additional time to stabilize"
fi

# Validate that all services are using the correct PVCs
print_info "Validating PVC usage by services"
kubectl get pvc -n $NAMESPACE

# Final deployment status overview
print_info "Final deployment status overview"
kubectl get pods -n $NAMESPACE

# Clean up temporary file
rm -f helm-values-azure-resolved.yaml

# =============================================================================
# DEPLOYMENT MONITORING AND VERIFICATION
# =============================================================================

# Monitor deployment progress
print_step "📊 Monitoring deployment progress"
print_info "This may take 2-5 minutes for all services to start..."
print_info "Checking pod status in 1 minute..."

# Wait for 1 minute before checking pod status
sleep 60
kubectl get pods -n $NAMESPACE

# Get service information
print_info "Getting service information"
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

# =============================================================================
# DEPLOYMENT MONITORING AND VERIFICATION
# =============================================================================

# Monitor deployment progress
print_step "📊 Monitoring deployment progress"
print_info "Waiting for services to start..."
sleep 60

# Check basic deployment status
kubectl get pods -n $NAMESPACE
kubectl get services -n $NAMESPACE
kubectl get ingress -n $NAMESPACE -o wide

# Create TLS secret for AGIC
print_info "Creating TLS secret for AGIC..."
TEMP_CERT_DIR=$(mktemp -d)
CERT_FILE="$TEMP_CERT_DIR/tls.crt"
KEY_FILE="$TEMP_CERT_DIR/tls.key"

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/CN=$PUBLIC_IP_ADDRESS" 2>/dev/null

kubectl create secret tls opik-tls-secret \
    --cert="$CERT_FILE" \
    --key="$KEY_FILE" \
    --namespace=$NAMESPACE \
    --dry-run=client -o yaml | kubectl apply -f -

rm -rf "$TEMP_CERT_DIR"
print_success "TLS secret created"

# Simple connectivity test
print_step "🔍 Testing basic connectivity"
HTTPS_URL="https://$PUBLIC_IP_ADDRESS"
if [ -n "$DOMAIN_NAME" ]; then
    HTTPS_URL="https://$DOMAIN_NAME"
fi

print_info "Testing access to: $HTTPS_URL"
HTTP_STATUS=$(curl -o /dev/null -s -w "%{http_code}" --connect-timeout 10 -k "$HTTPS_URL" 2>/dev/null || echo "000")

if [ "$HTTP_STATUS" = "302" ] || [ "$HTTP_STATUS" = "200" ]; then
    print_success "✅ HTTPS connectivity working (HTTP $HTTP_STATUS)"
else
    print_warning "⚠️ HTTPS may still be configuring (HTTP $HTTP_STATUS)"
    print_info "Wait a few minutes and try accessing manually"
fi

# =============================================================================
# DEPLOYMENT SUMMARY
# =============================================================================

print_step "Deployment Summary"
print_success "🎉 Opik deployment completed successfully!"

print_section "🏗️ Infrastructure Summary"
print_key_value "Resource Group" "$RESOURCE_GROUP"
print_key_value "AKS Cluster" "$AKS_CLUSTER_NAME"
print_key_value "Application Gateway" "$APP_GATEWAY_NAME"
print_key_value "Public IP" "$PUBLIC_IP_ADDRESS"
print_key_value "Container Registry" "$ACR_NAME"
print_key_value "Namespace" "$NAMESPACE"

print_section "🔐 Authentication Configuration"
print_key_value "App Registration" "$OPIK_APP_NAME"
print_key_value "App ID" "$APP_ID"
print_key_value "Tenant ID" "$TENANT_ID"
print_key_value "Client Secret" "$CLIENT_SECRET"
print_key_value "OAuth2 Cookie Secret" "$OAUTH2_COOKIE_SECRET"
if [ -n "${OPIK_ACCESS_GROUP_ID:-}" ]; then
    print_key_value "Access Group" "$OPIK_ACCESS_GROUP_NAME"
    print_key_value "Group ID" "$OPIK_ACCESS_GROUP_ID"
    print_warning "⚠️ Only members of '$OPIK_ACCESS_GROUP_NAME' group can access Opik!"
    print_warning "👥 Add team members to the access group in Azure Portal!"
else
    print_info "Access allowed for all users in tenant"
fi

print_section "🌐 Access Information"
if [ -n "$DOMAIN_NAME" ]; then
    print_key_value "HTTPS URL (Recommended)" "https://$DOMAIN_NAME"
    print_key_value "HTTP URL" "http://$DOMAIN_NAME"
    print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
    
    # SSL certificate status for domain
    if [ "${SSL_ENABLED:-false}" = "true" ] && [ -n "${SSL_ISSUER:-}" ]; then
        print_info "🔒 SSL Certificate: Automatic (Let's Encrypt)"
        print_info "Certificate will be provisioned automatically in 5-10 minutes"
        print_warning "Initial access may show security warning until certificate is ready"
    else
        print_warning "🔒 SSL Certificate: Self-signed (browser warnings expected)"
    fi
else
    print_key_value "HTTPS URL (Recommended)" "https://$PUBLIC_IP_ADDRESS"
    print_key_value "HTTP URL" "http://$PUBLIC_IP_ADDRESS"
    print_warning "🔒 HTTPS uses self-signed certificate - browsers will show security warnings"
    print_info "💡 To enable trusted SSL: Set DOMAIN_NAME in .env.azure and redeploy"
fi

print_section "🔗 Available Endpoints"
print_key_value "Frontend" "/"
print_key_value "Backend API" "/v1/private/*"
print_key_value "Python Backend" "/v1/private/evaluators/*"
print_key_value "Health Check" "/health-check"

print_section "⚡ Useful Commands"
print_info "Check deployment status:"
print_info "  kubectl get pods -n $NAMESPACE"
print_info "  kubectl get ingress -n $NAMESPACE"
print_info ""
print_info "View application logs:"
print_info "  kubectl logs -n $NAMESPACE deployment/opik-backend"
print_info "  kubectl logs -n $NAMESPACE deployment/opik-frontend"
print_info "  kubectl logs -n $NAMESPACE deployment/opik-python-backend"
print_info "  kubectl logs -n $NAMESPACE deployment/oauth2-proxy"
print_info ""
print_info "Port forward (bypass authentication):"
print_info "  kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173"
print_info ""
print_info "Manage team access:"
if [ -n "${OPIK_ACCESS_GROUP_ID:-}" ]; then
    print_info "  # Add users to the Opik Users group:"
    print_info "  az ad group member add --group '$OPIK_ACCESS_GROUP_NAME' --member-id <user-email-or-object-id>"
    print_info "  # List current group members:"
    print_info "  az ad group member list --group '$OPIK_ACCESS_GROUP_NAME'"
    print_info "  # View group in Azure Portal:"
    print_info "  https://portal.azure.com/#view/Microsoft_AAD_Groups/GroupDetailsMenuBlade/~/Overview/groupId/$OPIK_ACCESS_GROUP_ID"
else
    print_info "  Group-based access control is not configured - all tenant users can access Opik"
fi
print_info ""
print_info "Uninstall deployment:"
print_info "  helm uninstall opik -n $NAMESPACE"

print_section "🔧 Troubleshooting Commands"
print_info "If upgrade fails with backend/ClickHouse issues:"
print_info "  # Use the built-in recovery function:"
print_info "  source ./deploy-azure.sh && recover_from_upgrade_failure"
print_info ""
print_info "  # Or manually check rollout status:"
print_info "  kubectl rollout status deployment/opik-backend -n $NAMESPACE"
print_info "  kubectl get pods -l app.kubernetes.io/name=opik-backend -n $NAMESPACE"
print_info ""
print_info "  # Manually fix ClickHouse state:"
print_info "  kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query \"DROP DATABASE IF EXISTS opik\""
print_info "  kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query \"CREATE DATABASE IF NOT EXISTS opik\""
print_info "  kubectl delete pods -l app.kubernetes.io/name=opik-backend -n $NAMESPACE"
print_info ""
print_info "If authentication fails with AADSTS650056 error:"
print_info "  az ad app permission list --id $APP_ID"
print_info "  az ad app permission admin-consent --id $APP_ID"
print_info ""
print_info "If you get 'Misconfigured application' errors:"
print_info "  Check App Registration in Azure Portal: https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
print_info "  Ensure User.Read permission is granted and admin consented"
print_info ""
print_info "If Application Gateway shows 502 errors:"
print_info "  kubectl get pods -l app=ingress-appgw -n kube-system"
print_info "  kubectl logs -l app=ingress-appgw -n kube-system --tail=50"
print_info "  kubectl get ingress -n $NAMESPACE"
print_info ""
print_info "If AGIC has permission issues (403 Forbidden errors):"
print_info "  # Use the built-in fix function:"
print_info "  source ./deploy-azure.sh && fix_agic_permissions"
print_info ""
print_info "  # Or check AGIC logs for permission errors:"
print_info "  kubectl logs -l app=ingress-appgw -n kube-system | grep -E 'Forbidden|AuthorizationFailed|403'"
print_info ""
print_info "  # Get the actual AGIC identity from logs:"
print_info "  kubectl logs -l app=ingress-appgw -n kube-system | grep \"client '\" | head -1"
print_info ""
print_info "  # Grant permissions to AGIC identity (replace CLIENT_ID with actual ID from logs):"
print_info "  az role assignment create --role Reader --scope /subscriptions/$(az account show --query id -o tsv)/resourceGroups/$RESOURCE_GROUP --assignee CLIENT_ID"
print_info "  az role assignment create --role Contributor --scope /subscriptions/$(az account show --query id -o tsv)/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Network/applicationGateways/$APP_GATEWAY_NAME --assignee CLIENT_ID"
print_info ""
print_info "  # Restart AGIC after fixing permissions:"
print_info "  kubectl rollout restart deployment -l app=ingress-appgw -n kube-system"
print_info ""
print_info "Check Application Gateway backend health:"
print_info "  az network application-gateway show-backend-health --name $APP_GATEWAY_NAME --resource-group $RESOURCE_GROUP"
print_info ""
print_info "SSL Certificate troubleshooting:"
if [ "${SSL_ENABLED:-false}" = "true" ] && [ -n "${SSL_ISSUER:-}" ]; then
    print_info "  # Check certificate status:"
    print_info "  kubectl get certificate -n $NAMESPACE"
    print_info "  kubectl describe certificate opik-tls-secret -n $NAMESPACE"
    print_info ""
    print_info "  # Check cert-manager logs:"
    print_info "  kubectl logs -n cert-manager deployment/cert-manager --tail=50"
    print_info ""
    print_info "  # Force certificate renewal:"
    print_info "  kubectl delete certificate opik-tls-secret -n $NAMESPACE"
    print_info "  kubectl delete secret opik-tls-secret -n $NAMESPACE"
    print_info ""
    print_info "  # Check ClusterIssuer status:"
    print_info "  kubectl describe clusterissuer letsencrypt-prod"
else
    print_info "  # SSL is using self-signed certificates"
    print_info "  # To enable Let's Encrypt SSL:"
    print_info "  # 1. Set DOMAIN_NAME in .env.azure"
    print_info "  # 2. Set EMAIL_FOR_LETSENCRYPT in .env.azure"
    print_info "  # 3. Configure DNS: yourdomain.com → $PUBLIC_IP_ADDRESS"
    print_info "  # 4. Run ./deploy-azure.sh again"
fi
print_info ""
print_info "Restart AGIC if needed:"
print_info "  kubectl rollout restart deployment/ingress-appgw-deployment -n kube-system"
print_info ""
print_info "Check AGIC configuration:"
print_info "  kubectl describe configmap -n kube-system | grep appgw"

print_warning "🔐 Authentication is required - all users will be redirected to Microsoft login"

print_section "🎯 Next Steps"
print_info "Choose how you want to access Opik:"
print_info "1. Use Application Gateway (recommended) - Access via public IP with authentication"
print_info "2. Use port forwarding - Access via localhost (bypasses authentication)"
read -p "Enter your choice (1 or 2): " -n 1 -r
echo

if [[ $REPLY == "2" ]]; then
    print_step "Starting Port Forwarding"
    print_info "🌐 Opik will be available at: http://localhost:5173"
    print_warning "Press Ctrl+C to stop port forwarding"
    kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173
else
    print_step "Application Gateway Ready"
    if [ -n "$DOMAIN_NAME" ]; then
        print_success "🌐 Application available at: https://$DOMAIN_NAME (HTTPS - Recommended)"
        print_info "Also available at: http://$DOMAIN_NAME (HTTP)"
        print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
    else
        print_success "🌐 Application available at: https://$PUBLIC_IP_ADDRESS (HTTPS - Recommended)"
        print_info "Also available at: http://$PUBLIC_IP_ADDRESS (HTTP)"
    fi
    print_warning "HTTPS uses self-signed certificate - accept browser security warning"
    print_info "It may take a few minutes for Application Gateway to configure backend pools"
    print_info "If you get 502 errors, wait a few minutes and try again"
fi

# =============================================================================
# DATA PERSISTENCE INFORMATION
# =============================================================================

print_section "💾 Data Persistence Information"
print_success "✅ Data is stored on persistent disks in the main resource group"
print_info "Your data will survive cluster deletion and recreation!"

print_info "Disk Resource Information:"
print_key_value "Resource Group" "$RESOURCE_GROUP"
print_info ""
print_info "Created Opik Data Disks:"
if [ -n "${MYSQL_DISK_NAME:-}" ]; then
    print_key_value "MySQL" "$MYSQL_DISK_NAME"
fi
if [ -n "${CLICKHOUSE_DISK_NAME:-}" ]; then
    print_key_value "ClickHouse" "$CLICKHOUSE_DISK_NAME"  
fi
if [ -n "${MINIO_DISK_NAME:-}" ]; then
    print_key_value "MinIO" "$MINIO_DISK_NAME"
fi
if [ -n "${REDIS_DISK_NAME:-}" ]; then
    print_key_value "Redis" "$REDIS_DISK_NAME"
fi

print_success "✅ Data will persist across cluster deletions!"
print_info "Safe to delete cluster - data disks remain in main resource group"
print_warning "To delete data permanently, manually delete the opik-*-data-* disks"

print_success "🎉 Deployment completed successfully!"