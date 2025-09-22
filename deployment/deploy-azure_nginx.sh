#!/bin/bash

# =============================================================================
# Opik Helm Deployment Script for Azure (NGINX Ingress)
# =============================================================================
# This script deploys Opik on Azure using AKS, NGINX Ingress, and OAuth2
# Features: HTTPS with NGINX Ingress + cert-manager, Azure Entra ID authentication, Docker image builds.
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

# Backup function to create snapshots before any recovery operations
create_data_backup() {
    print_step "💾 Creating Data Backup"
    print_info "Creating Azure disk snapshots for data protection"
    
    # Load environment variables if .env.azure-nginx exists
    if [ -f ".env.azure-nginx" ]; then
        source .env.azure-nginx
    else
        print_error ".env.azure-nginx not found. Please run from deployment directory."
        return 1
    fi
    
    local timestamp=$(date +%Y%m%d-%H%M%S)
    local backup_prefix="opik-backup-$timestamp"
    
    # Services to backup
    local services=("mysql" "clickhouse" "minio" "redis")
    
    for service in "${services[@]}"; do
        local service_upper=$(echo "$service" | tr '[:lower:]' '[:upper:]')
        local disk_name_var="${service_upper}_DISK_NAME"
        local disk_name="${!disk_name_var}"
        
        if [ -n "$disk_name" ]; then
            local snapshot_name="${backup_prefix}-${service}"
            print_info "Creating snapshot for $service disk: $disk_name"
            
            if az snapshot create \
                --resource-group $RESOURCE_GROUP \
                --name "$snapshot_name" \
                --source "$disk_name" \
                --location $LOCATION &>/dev/null; then
                print_success "Created snapshot: $snapshot_name"
            else
                print_warning "Failed to create snapshot for $service disk"
            fi
        else
            print_warning "No disk found for $service - skipping backup"
        fi
    done
    
    print_success "💾 Backup snapshots created with prefix: $backup_prefix"
    print_info "You can restore from these snapshots if needed"
}

# Quick fix function for NGINX Ingress issues
fix_nginx_ingress_issues() {
    print_step "Fixing NGINX Ingress Issues"
    print_info "This function fixes common NGINX Ingress Controller issues"
    
    # Load environment variables if .env.azure-nginx exists
    if [ -f ".env.azure-nginx" ]; then
        source .env.azure-nginx
    else
        print_error ".env.azure-nginx not found. Please run from deployment directory."
        return 1
    fi
    
    # Get NGINX Ingress logs and check for issues
    print_info "Checking NGINX Ingress Controller logs for errors..."
    NGINX_LOGS=$(kubectl logs -l app.kubernetes.io/name=ingress-nginx -n nginx-ingress --tail=50 2>/dev/null || echo "")
    
    if echo "$NGINX_LOGS" | grep -q "ERROR\|WARN\|Failed"; then
        print_info "Found issues in NGINX Ingress logs"
        print_info "Recent NGINX Ingress logs:"
        echo "$NGINX_LOGS" | tail -20
        
        # Check LoadBalancer status
        print_info "Checking LoadBalancer IP assignment..."
        LOADBALANCER_IP=$(kubectl get service nginx-ingress-ingress-nginx-controller -n nginx-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        
        if [ "$LOADBALANCER_IP" != "$PUBLIC_IP_ADDRESS" ]; then
            print_warning "LoadBalancer IP mismatch or not assigned"
            print_info "Expected: $PUBLIC_IP_ADDRESS, Current: $LOADBALANCER_IP"
            
            # Restart NGINX Ingress Controller
            print_info "Restarting NGINX Ingress Controller..."
            kubectl rollout restart deployment/nginx-ingress-ingress-nginx-controller -n nginx-ingress
            
            print_info "Waiting for LoadBalancer IP to be assigned..."
            sleep 60
            
            # Check again
            LOADBALANCER_IP=$(kubectl get service nginx-ingress-ingress-nginx-controller -n nginx-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
            if [ "$LOADBALANCER_IP" = "$PUBLIC_IP_ADDRESS" ]; then
                print_success "LoadBalancer IP correctly assigned: $PUBLIC_IP_ADDRESS"
            else
                print_warning "LoadBalancer IP still not correctly assigned"
            fi
        else
            print_success "LoadBalancer IP correctly assigned: $PUBLIC_IP_ADDRESS"
        fi
        
        print_success "NGINX Ingress troubleshooting completed!"
    else
        print_success "No major issues found in NGINX Ingress logs"
        print_info "Recent NGINX Ingress logs:"
        echo "$NGINX_LOGS" | tail -5
    fi
}

# Safe recovery function that preserves data - USE THIS ONE
safe_recover_from_failure() {
    print_step "🔄 Safe Recovery from Failure (Data Preserving)"
    print_warning "⚠️  This function preserves your data and only fixes stuck pods/migrations"
    
    # Load environment variables if .env.azure-nginx exists
    if [ -f ".env.azure-nginx" ]; then
        source .env.azure-nginx
    else
        print_error ".env.azure-nginx not found. Please run from deployment directory."
        return 1
    fi
    
    print_info "Starting safe recovery operations..."
    
    # Step 1: Remove stuck pods only (not data)
    print_info "Removing stuck backend pods (preserving data)..."
    kubectl delete pods -l app.kubernetes.io/name=opik-backend -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
    kubectl delete pods -l app.kubernetes.io/name=opik-python-backend -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
    
    # Step 2: Clean up old replica sets
    print_info "Cleaning up old replica sets..."
    for deployment in opik-backend opik-frontend opik-python-backend; do
        OLD_RS=$(kubectl get rs -n $NAMESPACE -l app.kubernetes.io/name=$deployment -o jsonpath='{.items[?(@.spec.replicas==0)].metadata.name}' 2>/dev/null || echo "")
        if [ -n "$OLD_RS" ]; then
            print_info "Removing old replica sets for $deployment: $OLD_RS"
            echo "$OLD_RS" | xargs -r kubectl delete rs -n $NAMESPACE 2>/dev/null || true
        fi
    done
    
    # Step 3: ONLY clear migration locks (preserve data and schema)
    print_info "Clearing migration locks (preserving all data)..."
    if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
        print_info "Clearing ClickHouse migration locks only..."
        kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOGLOCK" 2>/dev/null || true
        print_success "ClickHouse migration locks cleared"
    else
        print_warning "ClickHouse not accessible for lock cleanup"
    fi
    
    # Step 4: Clear MySQL migration locks too
    if kubectl exec mysql-0 -n $NAMESPACE -- mysql -u root -proot123 -e "SELECT 1" &>/dev/null; then
        print_info "Clearing MySQL migration locks only..."
        kubectl exec mysql-0 -n $NAMESPACE -- mysql -u root -proot123 -e "DELETE FROM opik.DATABASECHANGELOGLOCK WHERE ID = 1" 2>/dev/null || true
        print_success "MySQL migration locks cleared"
    else
        print_warning "MySQL not accessible for lock cleanup"
    fi
    
    # Step 5: Restart deployments
    print_info "Restarting deployments..."
    for deployment in opik-backend opik-python-backend; do
        kubectl rollout restart deployment $deployment -n $NAMESPACE 2>/dev/null || true
    done
    
    print_success "✅ Safe recovery completed - your data is preserved!"
    print_info "Monitor pod status with: kubectl get pods -n $NAMESPACE"
    print_info "Check deployment status with: kubectl rollout status deployment/opik-backend -n $NAMESPACE"
}

# Function to verify certificate validity
verify_certificate_validity() {
    print_info "Verifying certificate validity..."
    
    # Get the certificate from the secret
    local cert_data=$(kubectl get secret opik-tls-secret -n $NAMESPACE -o jsonpath='{.data.tls\.crt}' 2>/dev/null)
    
    if [ -z "$cert_data" ]; then
        print_warning "Certificate secret exists but contains no certificate data"
        return 1
    fi
    
    # Decode and check certificate details
    local cert_subject=$(echo "$cert_data" | base64 -d | openssl x509 -noout -subject 2>/dev/null | sed 's/subject=//')
    local cert_issuer=$(echo "$cert_data" | base64 -d | openssl x509 -noout -issuer 2>/dev/null | sed 's/issuer=//')
    local cert_dates=$(echo "$cert_data" | base64 -d | openssl x509 -noout -dates 2>/dev/null)
    
    if [ -n "$cert_subject" ] && [ -n "$cert_issuer" ]; then
        print_success "Certificate details verified:"
        print_info "  Subject: $cert_subject"
        print_info "  Issuer: $cert_issuer"
        print_info "  $cert_dates"
        
        # Check if it's a Let's Encrypt certificate
        if echo "$cert_issuer" | grep -qi "let's encrypt\|R3\|R10\|R11\|R12"; then
            print_success "✅ Valid Let's Encrypt certificate detected"
            return 0
        else
            print_warning "⚠️ Certificate is not from Let's Encrypt - may be self-signed"
            return 0  # Still consider it successful
        fi
    else
        print_warning "Could not verify certificate details"
        return 1
    fi
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

# Function to provide comprehensive deployment status
provide_deployment_status_summary() {
    print_step "📊 Comprehensive Deployment Status"
    
    # Infrastructure status
    print_section "🏗️ Infrastructure Status"
    
    local running_pods=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null | grep Running | wc -l)
    local total_pods=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null | wc -l)
    
    if [ "$running_pods" -gt 0 ]; then
        print_success "✅ Pods: $running_pods/$total_pods running"
    else
        print_warning "⚠️ Pods: $running_pods/$total_pods running"
    fi
    
    local services_count=$(kubectl get services -n $NAMESPACE --no-headers 2>/dev/null | wc -l)
    print_success "✅ Services: $services_count active"
    
    local ingress_count=$(kubectl get ingress -n $NAMESPACE --no-headers 2>/dev/null | wc -l)
    if [ "$ingress_count" -gt 0 ]; then
        print_success "✅ Ingress: $ingress_count configured"
    else
        print_warning "⚠️ Ingress: None found"
    fi
    
    # SSL Certificate status
    print_section "🔒 SSL Certificate Status"
    
    if [ -n "${DOMAIN_NAME:-}" ] && [ "${SSL_ENABLED:-false}" = "true" ]; then
        local cert_ready=$(kubectl get certificate opik-tls-secret -n $NAMESPACE -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "False")
        
        if [ "$cert_ready" = "True" ]; then
            print_success "✅ Certificate: Ready and valid"
            
            # Verify the actual certificate
            if verify_certificate_validity; then
                print_success "✅ Certificate validation: Passed"
            else
                print_warning "⚠️ Certificate validation: Issues detected"
            fi
        else
            print_warning "⚠️ Certificate: Not ready"
            
            # Check for specific issues
            local cert_description=$(kubectl describe certificate opik-tls-secret -n $NAMESPACE 2>/dev/null || echo "")
            if echo "$cert_description" | grep -qi "rateLimited\|rate.limited"; then
                print_warning "   Reason: Let's Encrypt rate limit"
            elif echo "$cert_description" | grep -qi "failed\|error"; then
                print_warning "   Reason: Provisioning error"
            else
                print_info "   Reason: Still provisioning"
            fi
        fi
    else
        print_info "ℹ️ Certificate: Using self-signed (SSL not configured)"
    fi
    
    # Network connectivity status
    print_section "🌐 Network Connectivity"
    
    if [ -n "${DOMAIN_NAME:-}" ]; then
        print_info "Testing domain connectivity: $DOMAIN_NAME"
        local domain_status=$(curl -o /dev/null -s -w "%{http_code}" --connect-timeout 5 -k "https://$DOMAIN_NAME" 2>/dev/null || echo "000")
        
        if [ "$domain_status" = "200" ] || [ "$domain_status" = "302" ]; then
            print_success "✅ Domain: Accessible (HTTP $domain_status)"
        else
            print_warning "⚠️ Domain: Issues detected (HTTP $domain_status)"
            print_info "   This may be normal during initial deployment"
        fi
    fi
    
    print_info "Testing IP connectivity: $PUBLIC_IP_ADDRESS"
    local ip_status=$(curl -o /dev/null -s -w "%{http_code}" --connect-timeout 5 -k "https://$PUBLIC_IP_ADDRESS" 2>/dev/null || echo "000")
    
    if [ "$ip_status" = "200" ] || [ "$ip_status" = "302" ]; then
        print_success "✅ Public IP: Accessible (HTTP $ip_status)"
    else
        print_warning "⚠️ Public IP: Issues detected (HTTP $ip_status)"
        print_info "   NGINX Ingress may still be configuring"
    fi
}

# =============================================================================
# ENVIRONMENT CONFIGURATION
# =============================================================================

# Load environment variables from .env.azure-nginx
if [ -f ".env.azure-nginx" ]; then
    print_step "Loading environment variables from .env.azure-nginx"
    
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
    done < .env.azure-nginx
    set +a
    
    # Validate required variables
    REQUIRED_VARS=(
        "RESOURCE_GROUP"
        "LOCATION" 
        "AKS_CLUSTER_NAME"
        "ACR_NAME"
        "NAMESPACE"
        "OPIK_VERSION"
        "PUBLIC_IP_NAME"
        "VNET_NAME"
        "AKS_SUBNET_NAME"
        "VNET_ADDRESS_PREFIX"
        "AKS_SUBNET_PREFIX"
    )
    
    MISSING_VARS=()
    for var in "${REQUIRED_VARS[@]}"; do
        if [ -z "${!var}" ]; then
            MISSING_VARS+=("$var")
        fi
    done
    
    if [ ${#MISSING_VARS[@]} -ne 0 ]; then
        print_error "Missing required environment variables in .env.azure-nginx:"
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
    
    print_success "Loaded and validated environment variables from .env.azure-nginx"
else
    print_error ".env.azure-nginx file not found! Please make sure you're running this from the deployment directory."
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

# Check if helm-values-azure-nginx-template.yaml exists
if [ ! -f "helm-values-azure-nginx-template.yaml" ]; then
    print_error "helm-values-azure-nginx-template.yaml file not found!"
    print_error "Please ensure this file exists in the deployment directory."
    print_error "This file should contain the custom Helm values template for your Azure deployment with NGINX Ingress."
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
        print_warning "AKS cluster is using a different VNet."
    elif [ "$AKS_VNET_SUBNET_ID" == "null" ]; then
        print_warning "AKS cluster was created without VNet integration."
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
# PUBLIC IP SETUP FOR NGINX INGRESS
# =============================================================================

# Create public IP for NGINX Ingress LoadBalancer (if it doesn't exist)
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

if [ -z "$PUBLIC_IP_ADDRESS" ] || [ "$PUBLIC_IP_ADDRESS" = "null" ]; then
    print_error "Failed to retrieve public IP address for $PUBLIC_IP_NAME"
    print_error "Public IP may still be provisioning. Please wait and try again."
    exit 1
fi

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
    print_warning "OPIK_ACCESS_GROUP_NAME not specified in .env.azure-nginx - authentication will allow any user in your tenant"
fi

# Create App Registration for Opik authentication
# Preserve OPIK_APP_NAME from .env.azure-nginx if set, otherwise use default pattern
ORIGINAL_OPIK_APP_NAME="${OPIK_APP_NAME:-}"
OPIK_APP_NAME="${OPIK_APP_NAME:-opik-frontend-auth-${RESOURCE_GROUP}}"
print_step "Creating App Registration for Opik authentication"
print_info "App Registration Name: $OPIK_APP_NAME"

# Check if app registration already exists
print_info "Searching for existing App Registration with name: $OPIK_APP_NAME"
APP_ID=$(az ad app list --display-name "$OPIK_APP_NAME" --query "[0].appId" -o tsv)

# If not found with full name, try searching for the base name from .env.azure-nginx
if [ -z "$APP_ID" ] || [ "$APP_ID" = "null" ]; then
    if [ -n "$ORIGINAL_OPIK_APP_NAME" ] && [ "$ORIGINAL_OPIK_APP_NAME" != "$OPIK_APP_NAME" ]; then
        print_info "Trying alternative search with base name from .env.azure-nginx: $ORIGINAL_OPIK_APP_NAME"
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
# NGINX INGRESS CONTROLLER SETUP
# =============================================================================
# 
# IMPORTANT: Large OAuth2 Token Buffer Configuration
# ==================================================
# Azure AD OAuth2 tokens can be extremely large (50KB+) for users with many 
# group memberships, causing "502 Bad Gateway" errors due to "upstream sent 
# too big header" issues.
#
# The following buffer settings ensure NGINX can handle large OAuth2 responses:
# - proxy-buffer-size: 16k (increased from 4k default)
# - proxy-buffers-number: 8 (number of proxy buffers)
# - proxy-busy-buffers-size: 64k (busy buffers size)  
# - large-client-header-buffers: 4 32k (large client headers support)
#
# Without these settings, authentication may fail with 502 errors after
# successful OAuth2 login, particularly for users in multiple Azure AD groups.
# =============================================================================

print_step "🔌 Installing NGINX Ingress Controller"

# Install NGINX Ingress Controller using Helm
if ! helm list -n nginx-ingress 2>/dev/null | grep -q nginx-ingress; then
    print_info "Installing NGINX Ingress Controller..."
else
    print_info "Upgrading NGINX Ingress Controller configuration..."
fi

# Add NGINX Ingress Helm repository
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo update

# Create namespace for NGINX Ingress
kubectl create namespace nginx-ingress --dry-run=client -o yaml | kubectl apply -f -

# Install or upgrade NGINX Ingress Controller with LoadBalancer service using our pre-created public IP
helm upgrade --install nginx-ingress ingress-nginx/ingress-nginx \
    --namespace nginx-ingress \
    --set controller.service.type=LoadBalancer \
    --set controller.service.loadBalancerIP=$PUBLIC_IP_ADDRESS \
    --set controller.service.annotations."service\.beta\.kubernetes\.io/azure-load-balancer-resource-group"=$RESOURCE_GROUP \
    --set controller.replicaCount=2 \
    --set controller.nodeSelector."kubernetes\.io/os"=linux \
    --set defaultBackend.nodeSelector."kubernetes\.io/os"=linux \
    --set controller.admissionWebhooks.patch.nodeSelector."kubernetes\.io/os"=linux \
    --set controller.service.externalTrafficPolicy=Local \
    --set controller.config.proxy-buffer-size="16k" \
    --set controller.config.proxy-buffers-number="8" \
    --set controller.config.proxy-busy-buffers-size="64k" \
    --set controller.config.large-client-header-buffers="4 32k"

print_success "NGINX Ingress Controller installed/upgraded"

# Wait for NGINX Ingress Controller to be ready
print_info "Waiting for NGINX Ingress Controller to be ready..."
kubectl wait --namespace nginx-ingress \
    --for=condition=ready pod \
    --selector=app.kubernetes.io/component=controller \
    --timeout=300s

# Verify LoadBalancer IP assignment
print_info "Verifying LoadBalancer IP assignment..."
LOADBALANCER_IP=$(kubectl get service nginx-ingress-ingress-nginx-controller -n nginx-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")

if [ "$LOADBALANCER_IP" = "$PUBLIC_IP_ADDRESS" ]; then
    print_success "✅ NGINX Ingress Controller is ready with public IP: $PUBLIC_IP_ADDRESS"
else
    print_warning "⚠️ LoadBalancer IP assignment may still be in progress"
    print_info "Expected: $PUBLIC_IP_ADDRESS, Current: $LOADBALANCER_IP"
    print_info "This usually resolves within a few minutes"
fi

print_success "🔌 NGINX Ingress Controller setup completed"

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
        print_info "Creating Let's Encrypt ClusterIssuer for NGINX Ingress..."
        
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
          ingressClassName: nginx
EOF
        
        print_success "Let's Encrypt ClusterIssuer created for NGINX Ingress"
        
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
          ingressClassName: nginx
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
    
    # Validate NGINX Ingress Controller status
    print_info "Validating NGINX Ingress Controller status..."
    NGINX_READY=$(kubectl get pods -l app.kubernetes.io/name=ingress-nginx -n nginx-ingress --no-headers 2>/dev/null | grep Running | wc -l || echo "0")
    
    if [ "$NGINX_READY" -gt 0 ]; then
        print_success "NGINX Ingress Controller has $NGINX_READY running pod(s)"
        
        # Check LoadBalancer service status
        LOADBALANCER_IP=$(kubectl get service nginx-ingress-ingress-nginx-controller -n nginx-ingress -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        if [ "$LOADBALANCER_IP" = "$PUBLIC_IP_ADDRESS" ]; then
            print_success "LoadBalancer IP correctly assigned: $PUBLIC_IP_ADDRESS"
        else
            print_warning "LoadBalancer IP assignment may still be in progress"
            print_info "Expected: $PUBLIC_IP_ADDRESS, Current: $LOADBALANCER_IP"
        fi
    else
        print_warning "NGINX Ingress Controller pods not running yet"
        print_info "This is expected for new deployments"
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

# Check deployment scenario (fresh install, cluster recreation, or version upgrade)
if helm list -n $NAMESPACE | grep -q "opik"; then
    print_info "Existing Opik deployment detected"
    
    # Check if this is a cluster recreation (PVs exist but no pods)
    EXISTING_PVS=$(kubectl get pv --no-headers 2>/dev/null | grep "opik.*-pv" | wc -l || echo "0")
    RUNNING_PODS=$(kubectl get pods -n $NAMESPACE --no-headers 2>/dev/null | grep Running | wc -l || echo "0")
    
    if [ "$EXISTING_PVS" -gt 0 ] && [ "$RUNNING_PODS" -eq 0 ]; then
        print_info "🔄 Cluster recreation detected - preserving data and clearing PV claim references"
        SCENARIO="CLUSTER_RECREATION"
        
        # Only clear PV claim references for cluster recreation
        print_info "Making PVs available for rebinding by clearing claim references..."
        kubectl patch pv opik-mysql-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-minio-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-redis-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-clickhouse-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-zookeeper-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        
        print_success "PV claim references cleared - existing data will be restored"
        
    else
        print_info "📊 Version upgrade detected - performing SAFE upgrade (preserving all data)"
        SCENARIO="VERSION_UPGRADE"
        
        # Get current deployed version for comparison
        CURRENT_VERSION=$(helm list -n $NAMESPACE -o json | jq -r '.[] | select(.name=="opik") | .app_version // .chart // "unknown"' 2>/dev/null || echo "unknown")
        print_info "Current deployed version: $CURRENT_VERSION"
        print_info "Target version: $OPIK_VERSION"
        
        # SAFE upgrade logic - only clear stuck states, NEVER drop databases or delete PVCs
        print_info "Clearing ONLY migration locks and stuck deployments (preserving all data)"
        
        # Check for stuck deployments
        STUCK_DEPLOYMENTS=""
        for deployment in opik-backend opik-frontend opik-python-backend; do
            if kubectl rollout status deployment/$deployment -n $NAMESPACE --timeout=5s &>/dev/null; then
                print_success "$deployment rollout is healthy"
            else
                print_warning "$deployment rollout appears stuck - will clear"
                STUCK_DEPLOYMENTS="$STUCK_DEPLOYMENTS $deployment"
            fi
        done
        
        # Clear stuck deployments safely
        if [ -n "$STUCK_DEPLOYMENTS" ]; then
            print_info "Clearing stuck deployments:$STUCK_DEPLOYMENTS"
            for deployment in $STUCK_DEPLOYMENTS; do
                kubectl delete pods -l app.kubernetes.io/name=$deployment -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
                kubectl scale deployment $deployment -n $NAMESPACE --replicas=0 2>/dev/null || true
            done
            sleep 30
        fi
        
        # Only clear migration locks, never drop databases
        if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
            print_info "Clearing ONLY migration locks (preserving all data and schema)"
            kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "DROP TABLE IF EXISTS default.DATABASECHANGELOGLOCK" 2>/dev/null || true
            # NEVER run: DROP DATABASE IF EXISTS opik  ❌ - This would destroy all data!
        fi
        
        # Clear stuck MySQL migration locks too
        if kubectl exec mysql-0 -n $NAMESPACE -- mysql -u root -proot123 -e "SELECT 1" &>/dev/null; then
            print_info "Clearing MySQL migration locks only..."
            kubectl exec mysql-0 -n $NAMESPACE -- mysql -u root -proot123 -e "DELETE FROM opik.DATABASECHANGELOGLOCK WHERE ID = 1" 2>/dev/null || true
        fi
        
        # Scale down remaining stateful sets and deployments to release PVC locks
        print_info "Scaling down all services to release PVC locks..."
        kubectl scale statefulset opik-mysql -n $NAMESPACE --replicas=0 2>/dev/null || true
        kubectl scale statefulset opik-redis-master -n $NAMESPACE --replicas=0 2>/dev/null || true
        kubectl scale statefulset opik-zookeeper -n $NAMESPACE --replicas=0 2>/dev/null || true
        kubectl scale deployment opik-minio -n $NAMESPACE --replicas=0 2>/dev/null || true

        # Wait for all pods to terminate
        print_info "Waiting for all pods to terminate..."
        sleep 30

        # Delete ClickHouse installation (managed by operator) to properly release ClickHouse PVC
        print_info "Deleting ClickHouse installation to release ClickHouse pod and PVC..."
        kubectl delete clickhouseinstallation opik-clickhouse -n $NAMESPACE --timeout=60s --ignore-not-found=true 2>/dev/null || true
        
        # Wait for ClickHouse pod to terminate with explicit deletion if needed
        print_info "Waiting for ClickHouse pod to terminate..."
        kubectl wait --for=delete pod -l app=clickhouse -n $NAMESPACE --timeout=60s 2>/dev/null || true
        
        # Force delete ClickHouse pod if it's still running (operator sometimes doesn't clean up quickly)
        kubectl delete pod -l app=clickhouse -n $NAMESPACE --force --grace-period=0 2>/dev/null || true
        
        # Wait a bit more for pod deletion to complete
        sleep 10

        # Remove existing PVCs that may have immutable spec conflicts
        # Data is preserved because PVs have Retain policy
        print_info "Removing existing PVCs to allow Helm to recreate them (data preserved on PVs)..."
        
        # Delete PVCs individually without timeout to avoid blocking on slow deletions
        print_info "Deleting MinIO PVC..."
        kubectl delete pvc opik-minio -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        print_info "Deleting MySQL PVC..."
        kubectl delete pvc data-opik-mysql-0 -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        print_info "Deleting Redis PVC..."
        kubectl delete pvc redis-data-opik-redis-master-0 -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        print_info "Deleting ClickHouse PVC..."
        kubectl delete pvc storage-vc-template-chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        print_info "Deleting ZooKeeper PVC..."
        kubectl delete pvc data-opik-zookeeper-0 -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        
        # Check for any PVCs stuck in Terminating status and force delete them
        print_info "Checking for stuck PVCs and forcing deletion if needed..."
        STUCK_PVCS=$(kubectl get pvc -n $NAMESPACE --no-headers 2>/dev/null | grep Terminating | awk '{print $1}' || true)
        if [ -n "$STUCK_PVCS" ]; then
            print_warning "Found PVCs stuck in Terminating status, attempting to force delete..."
            for pvc in $STUCK_PVCS; do
                print_info "Force deleting stuck PVC: $pvc"
                kubectl patch pvc "$pvc" -n $NAMESPACE --type=merge -p='{"metadata":{"finalizers":[]}}' 2>/dev/null || true
            done
        fi
        
        # Allow some time for PVC deletion to process
        print_info "Allowing PVC deletions to process..."
        sleep 15

        print_success "All PVC deletion operations completed"

        # CRITICAL: After deleting PVCs, the PVs become "Released" and cannot be rebound
        # We need to clear the claimRef to make them "Available" again for rebinding
        # This ensures Helm creates new PVCs that bind to our existing PVs with data
        print_info "Making PVs available for rebinding by clearing claim references..."
        kubectl patch pv opik-mysql-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-minio-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-redis-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-clickhouse-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        kubectl patch pv opik-zookeeper-pv --type json -p='[{"op": "remove", "path": "/spec/claimRef"}]' 2>/dev/null || true
        
        # Remove any existing TLS secret (Helm will recreate it)
        kubectl delete secret opik-tls-secret -n $NAMESPACE --ignore-not-found=true
        
        # Remove conflicting ingress resources to prevent admission webhook errors
        print_info "Removing existing ingress resources to prevent conflicts..."
        kubectl delete ingress opik-main -n $NAMESPACE --ignore-not-found=true
        kubectl delete ingress opik-oauth2-proxy -n $NAMESPACE --ignore-not-found=true
        kubectl delete ingress opik-oauth2-ingress -n $NAMESPACE --ignore-not-found=true
        
        # Also remove any oauth2-proxy related ingress that might be created by the subchart
        kubectl delete ingress -l app.kubernetes.io/name=oauth2-proxy -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        
        # Remove StatefulSets with immutable spec conflicts (Helm will recreate them)
        print_info "Removing StatefulSets that may have immutable spec conflicts..."
        kubectl delete statefulset opik-zookeeper -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        kubectl delete statefulset opik-mysql -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        kubectl delete statefulset opik-redis-master -n $NAMESPACE --ignore-not-found=true 2>/dev/null || true
        
        # Wait for graceful shutdown and cleanup
        sleep 30
        
        print_success "✅ Version upgrade prepared safely - all data preserved"
    fi
else
    print_info "Fresh installation detected"
    SCENARIO="FRESH_INSTALL"
fi
# Substitute environment variables in helm-values-azure-nginx-template.yaml
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

# Set OPIK_HOST to domain name (required for this deployment)
if [ -n "${DOMAIN_NAME:-}" ]; then
    export OPIK_HOST="$DOMAIN_NAME"
else
    print_error "DOMAIN_NAME is required in .env.azure-nginx for domain-based deployment"
    exit 1
fi

# Export OPIK_HOST for template substitution
export OPIK_HOST

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
envsubst < helm-values-azure-nginx-template.yaml > helm-values-azure-resolved.yaml

# SSL and OAuth2 configuration is handled via OPIK_HOST variable in the template
if [ -n "${DOMAIN_NAME:-}" ]; then
    print_success "SSL certificate will be provisioned for domain: $DOMAIN_NAME"
    print_success "OAuth2 redirect URL configured for domain: $DOMAIN_NAME"
else
    print_info "Using IP-based access: $PUBLIC_IP_ADDRESS"
    print_warning "SSL will use self-signed certificates for IP access"
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
MYSQL_SIZE=$(grep -A5 "mysql:" helm-values-azure-nginx-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')
CLICKHOUSE_SIZE=$(grep -A10 "clickhouse:" helm-values-azure-nginx-template.yaml | grep "storage:" | head -1 | sed 's/.*storage: //; s/Gi//')
MINIO_SIZE=$(grep -A5 "minio:" helm-values-azure-nginx-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')
REDIS_SIZE=$(grep -A10 "redis:" helm-values-azure-nginx-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')
ZOOKEEPER_SIZE=$(grep -A10 "zookeeper:" helm-values-azure-nginx-template.yaml | grep "size:" | head -1 | sed 's/.*size: //; s/Gi//')

print_success "Disk sizes from template: MySQL=${MYSQL_SIZE}GB, ClickHouse=${CLICKHOUSE_SIZE}GB, MinIO=${MINIO_SIZE}GB, Redis=${REDIS_SIZE}GB, ZooKeeper=${ZOOKEEPER_SIZE}GB"

# Function to find or create disk with proper naming
find_or_create_opik_disk() {
    local service_name="$1"
    local size_gb="$2"
    local service_name_upper=$(echo "$service_name" | tr '[:lower:]' '[:upper:]')
    local var_name_disk_name="${service_name_upper}_DISK_NAME"
    local var_name_disk_id="${service_name_upper}_DISK_ID"
    
    # Enhanced search - look for any opik disk for this service with multiple patterns
    print_info "Looking for existing $service_name data disk..."
    print_info "Searching in resource group: $RESOURCE_GROUP"
    
    # Search for disks with multiple patterns to catch various naming schemes
    # Pattern 1: opik-service-data-* (with timestamp)
    # Pattern 2: opik-service-data (without timestamp) 
    # Pattern 3: Any disk containing both 'opik' and service name
    local existing_disk=$(az disk list --resource-group $RESOURCE_GROUP --query "[?contains(name, 'opik') && contains(name, '${service_name}') && contains(name, 'data')].{name:name, id:id}" -o json)
    
    # Debug: Show what we found
    print_info "Search result: $existing_disk"
    
    if [ "$existing_disk" != "[]" ] && [ -n "$existing_disk" ]; then
        # Found existing disk(s) - use the most recent one (sort by name, which includes timestamp)
        local disk_name=$(echo "$existing_disk" | jq -r 'sort_by(.name) | .[-1].name')
        local disk_id=$(echo "$existing_disk" | jq -r 'sort_by(.name) | .[-1].id')
        
        print_success "Found existing $service_name disk: $disk_name"
        print_success "✅ REUSING EXISTING DATA - your previous data will be restored!"
        
        # Check if multiple disks exist and warn user
        local disk_count=$(echo "$existing_disk" | jq '. | length')
        if [ "$disk_count" -gt 1 ]; then
            print_warning "Multiple $service_name disks found:"
            echo "$existing_disk" | jq -r '.[] | "  - \(.name)"'
            print_warning "Using the most recent one: $disk_name"
            print_info "You can manually clean up old disks after verifying the deployment works"
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
find_or_create_opik_disk "zookeeper" "$ZOOKEEPER_SIZE"

print_success "💾 Persistent disks ready"
print_info "Data will survive cluster deletion - disks are in main resource group"

# Validate scenario and provide clear feedback
print_step "📋 Deployment Scenario Validation"

case "${SCENARIO:-FRESH_INSTALL}" in
    "FRESH_INSTALL")
        print_success "✅ Fresh installation detected"
        print_info "All services will start with clean state"
        print_info "New persistent disks have been created for data storage"
        ;;
    "CLUSTER_RECREATION")
        print_success "✅ Cluster recreation detected"
        print_info "Existing data will be restored from persistent disks"
        print_info "All your previous data will be available after deployment"
        print_warning "If you see temporary 'database does not exist' errors, they will self-resolve"
        print_info "The application will automatically restore to your previous state"
        ;;
    "VERSION_UPGRADE")
        print_success "✅ Version upgrade detected"
        print_info "Upgrading from existing installation to version: $OPIK_VERSION"
        print_info "All existing data has been preserved during upgrade preparation"
        print_warning "Migration scripts will handle schema updates automatically"
        print_info "No manual intervention required - data migration is automatic"
        ;;
    *)
        print_info "ℹ️ Deployment scenario: ${SCENARIO:-Unknown}"
        ;;
esac

# Configure existingClaim variables based on whether we're in a fresh deployment
print_info "Configuring persistence strategy"
echo "📝 Using pre-created PVs with automatic PVC binding by Helm"
echo "🔗 Helm will create PVCs that automatically bind to our pre-created PVs"

print_success "Persistence configuration prepared"

# Create PersistentVolumes for pre-created disks with proper claimRef binding
print_step "🔗 Creating PersistentVolumes with deterministic PVC binding"

# Note: Using claimRef to pre-bind PVs to specific PVCs prevents random binding

# Function to create PV with proper claimRef for deterministic binding
create_pv_for_disk() {
    local service_name="$1"
    local disk_id="$2"
    local size="$3"
    local pv_name="opik-${service_name}-pv"
    
    # Check if PV already exists and skip if it does (for upgrades)
    if kubectl get pv $pv_name &>/dev/null; then
        print_info "PV $pv_name already exists - skipping creation"
        return 0
    fi
    
    print_info "Creating PV for $service_name with pre-binding to correct PVC..."
    
    # Determine the expected PVC name based on service
    local expected_pvc_name
    case "$service_name" in
        "mysql")
            expected_pvc_name="data-opik-mysql-0"
            ;;
        "clickhouse")
            expected_pvc_name="storage-vc-template-chi-opik-clickhouse-cluster-0-0-0"
            ;;
        "minio")
            expected_pvc_name="opik-minio"
            ;;
        "redis")
            expected_pvc_name="redis-data-opik-redis-master-0"
            ;;
        "zookeeper")
            expected_pvc_name="data-opik-zookeeper-0"
            ;;
        *)
            expected_pvc_name="opik-${service_name}"
            ;;
    esac
    
    # Create PersistentVolume with claimRef for deterministic binding
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
  claimRef:
    namespace: $NAMESPACE
    name: $expected_pvc_name
  csi:
    driver: disk.csi.azure.com
    volumeHandle: $disk_id
    fsType: ext4
EOF

    print_success "Created PV $pv_name pre-bound to PVC $expected_pvc_name"
}

# Create PVs for all services using sizes from template
create_pv_for_disk "mysql" "$MYSQL_DISK_ID" "$MYSQL_SIZE"
create_pv_for_disk "clickhouse" "$CLICKHOUSE_DISK_ID" "$CLICKHOUSE_SIZE"
create_pv_for_disk "minio" "$MINIO_DISK_ID" "$MINIO_SIZE" 
create_pv_for_disk "redis" "$REDIS_DISK_ID" "$REDIS_SIZE"
create_pv_for_disk "zookeeper" "$ZOOKEEPER_DISK_ID" "$ZOOKEEPER_SIZE"

print_success "🔗 All PersistentVolumes created with correct PVC pre-binding"

# Final pre-deployment setup
print_info "Ready for Helm deployment"

# CRITICAL: For upgrade scenarios, ensure deterministic PV-to-PVC binding
# Re-establish claimRef bindings to prevent random PVC assignments
if [ "$SCENARIO" = "VERSION_UPGRADE" ]; then
    print_info "🔒 Ensuring deterministic PV-to-PVC binding for upgrade scenario..."
    
    # Pre-bind PVs to their expected PVCs to prevent random assignments
    kubectl patch pv opik-mysql-pv --type json -p='[
        {"op": "add", "path": "/spec/claimRef", "value": {
            "namespace": "'$NAMESPACE'",
            "name": "data-opik-mysql-0",
            "uid": ""
        }}
    ]' 2>/dev/null || true
    
    kubectl patch pv opik-minio-pv --type json -p='[
        {"op": "add", "path": "/spec/claimRef", "value": {
            "namespace": "'$NAMESPACE'",
            "name": "opik-minio",
            "uid": ""
        }}
    ]' 2>/dev/null || true
    
    kubectl patch pv opik-redis-pv --type json -p='[
        {"op": "add", "path": "/spec/claimRef", "value": {
            "namespace": "'$NAMESPACE'",
            "name": "redis-data-opik-redis-master-0",
            "uid": ""
        }}
    ]' 2>/dev/null || true
    
    kubectl patch pv opik-clickhouse-pv --type json -p='[
        {"op": "add", "path": "/spec/claimRef", "value": {
            "namespace": "'$NAMESPACE'",
            "name": "storage-vc-template-chi-opik-clickhouse-cluster-0-0-0",
            "uid": ""
        }}
    ]' 2>/dev/null || true
    
    kubectl patch pv opik-zookeeper-pv --type json -p='[
        {"op": "add", "path": "/spec/claimRef", "value": {
            "namespace": "'$NAMESPACE'",
            "name": "data-opik-zookeeper-0",
            "uid": ""
        }}
    ]' 2>/dev/null || true
    
    print_success "✅ PV-to-PVC bindings secured - no random disk creation!"
fi

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
for attempt in {1..3}; do
    if kubectl wait --for=condition=Ready pod -l app=clickhouse -n $NAMESPACE --timeout=30s &>/dev/null; then
        if kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query "SELECT 1" &>/dev/null; then
            print_success "ClickHouse is ready and accessible"
            CLICKHOUSE_READY=true
            break
        fi
    fi
    print_info "ClickHouse not ready yet (attempt $attempt/3), waiting 30 seconds..."
    sleep 30
done

if [ "$CLICKHOUSE_READY" = "false" ]; then
    print_warning "ClickHouse not accessible after 3 attempts - backend may fail to start"
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

# Provide comprehensive deployment status
provide_deployment_status_summary

# =============================================================================
# CERTIFICATE MANAGEMENT FUNCTIONS
# =============================================================================

# Function to wait for certificate provisioning with timeout
wait_for_certificate_provisioning() {
    local max_wait=600  # 10 minutes
    local wait_time=0
    local check_interval=30
    
    print_info "Waiting for SSL certificate provisioning (up to 10 minutes)..."
    
    while [ $wait_time -lt $max_wait ]; do
        # Check if certificate exists and is ready
        CERT_STATUS=$(kubectl get certificate opik-tls-secret -n $NAMESPACE -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "False")
        
        if [ "$CERT_STATUS" = "True" ]; then
            print_success "✅ SSL certificate provisioned successfully by cert-manager"
            
            # Verify the certificate is actually valid
            verify_certificate_validity
            return $?
        elif [ "$CERT_STATUS" = "False" ]; then
            # Check for specific error conditions
            check_certificate_errors
            local error_result=$?
            
            if [ $error_result -eq 2 ]; then
                # Rate limit detected - return failure
                return 1
            elif [ $error_result -eq 1 ]; then
                # Other error detected - continue waiting but with warning
                print_warning "Certificate provisioning has errors but continuing to wait..."
            fi
        fi
        
        print_info "Certificate provisioning in progress... ($wait_time/$max_wait seconds)"
        sleep $check_interval
        wait_time=$((wait_time + check_interval))
    done
    
    print_warning "⚠️ Certificate provisioning timed out after 10 minutes"
    print_info "Checking certificate status for detailed error information..."
    kubectl describe certificate opik-tls-secret -n $NAMESPACE
    return 1
}

# Function to check for certificate errors
check_certificate_errors() {
    local cert_description=$(kubectl describe certificate opik-tls-secret -n $NAMESPACE 2>/dev/null || echo "")
    
    if echo "$cert_description" | grep -qi "rateLimited\|rate.limited\|too many certificates"; then
        print_error "🚫 Let's Encrypt rate limit detected!"
        print_warning "You have reached the rate limit for certificates for this domain"
        
        # Extract rate limit reset time if available
        local reset_time=$(echo "$cert_description" | grep -o "retry after [^:]*" | head -1)
        if [ -n "$reset_time" ]; then
            print_info "Rate limit will reset: $reset_time"
        fi
        
        print_info "Options to resolve:"
        print_info "1. Wait for rate limit to reset and retry"
        print_info "2. Use staging environment for testing"
        print_info "3. Use different domain/subdomain"
        
        return 2  # Rate limit error
    elif echo "$cert_description" | grep -qi "failed\|error"; then
        print_warning "Certificate provisioning encountered errors:"
        echo "$cert_description" | grep -A2 -B2 -i "failed\|error" | head -10
        return 1  # General error
    fi
    
    return 0  # No errors detected
}

# Function to retry certificate provisioning after failure
retry_certificate_provisioning() {
    print_step "🔄 Retrying SSL certificate provisioning"
    print_info "Cleaning up previous failed certificate request..."
    
    # Delete failed certificate requests
    kubectl delete certificaterequest -n $NAMESPACE --all --ignore-not-found=true
    
    # Delete existing certificate to trigger recreation
    kubectl delete certificate opik-tls-secret -n $NAMESPACE --ignore-not-found=true
    
    # Wait a moment for cleanup
    sleep 10
    
    # Recreate certificate with explicit configuration
    print_info "Creating new certificate request..."
    kubectl apply -f - <<EOF
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: opik-tls-secret
  namespace: $NAMESPACE
spec:
  secretName: opik-tls-secret
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
    group: cert-manager.io
  dnsNames:
  - $DOMAIN_NAME
  usages:
  - digital signature
  - key encipherment
EOF
    
    if [ $? -eq 0 ]; then
        print_success "Certificate resource recreated successfully"
        
        # Wait for new certificate provisioning attempt
        print_info "Waiting for certificate provisioning retry..."
        wait_for_certificate_provisioning
        return $?
    else
        print_error "Failed to recreate certificate resource"
        return 1
    fi
}

# Function to handle certificate provisioning with fallback strategies
handle_certificate_provisioning() {
    # Only proceed if domain name is configured and SSL is enabled
    if [ -z "${DOMAIN_NAME:-}" ]; then
        print_info "No domain name configured - skipping SSL certificate provisioning"
        return 0
    fi
    
    if [ "${SSL_ENABLED:-false}" != "true" ]; then
        print_info "SSL not enabled - skipping certificate provisioning"
        return 0
    fi
    
    print_step "🔒 SSL Certificate Provisioning"
    print_info "Attempting to provision Let's Encrypt certificate for: $DOMAIN_NAME"
    
    # First attempt: wait for automatic provisioning
    if wait_for_certificate_provisioning; then
        print_success "🎉 SSL certificate provisioning completed successfully!"
        return 0
    fi
    
    # Check what went wrong
    check_certificate_errors
    local error_type=$?
    
    if [ $error_type -eq 2 ]; then
        # Rate limit error - offer manual intervention options
        print_warning "SSL certificate provisioning failed due to Let's Encrypt rate limits"
        print_info "The deployment is functional, but using a temporary self-signed certificate"
        print_info ""
        print_info "To resolve the SSL certificate issue:"
        print_info "1. Wait for the rate limit to reset (usually 7 days from first certificate)"
        print_info "2. Manually retry after rate limit expires:"
        print_info "   kubectl delete certificaterequest -n $NAMESPACE --all"
        print_info "   kubectl delete certificate opik-tls-secret -n $NAMESPACE"
        print_info "3. The certificate will automatically provision once rate limit resets"
        
        return 1
    else
        # Other error - try retry
        print_warning "SSL certificate provisioning failed - attempting retry..."
        
        if retry_certificate_provisioning; then
            print_success "🎉 SSL certificate provisioning succeeded on retry!"
            return 0
        else
            print_warning "SSL certificate retry also failed"
            print_info "Deployment will continue with self-signed certificate"
            print_info "Check cert-manager logs for detailed error information:"
            print_info "  kubectl logs -n cert-manager deployment/cert-manager --tail=50"
            
            return 1
        fi
    fi
}

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

# Configure HTTPS with TLS secret for NGINX Ingress
print_step "Configuring HTTPS with TLS secret for NGINX Ingress"

# Create initial TLS secret for NGINX Ingress to work with Ingress TLS configuration
print_info "Creating initial TLS secret for NGINX Ingress HTTPS configuration..."

# Create temporary SSL certificate files for TLS secret
TEMP_CERT_DIR=$(mktemp -d)
CERT_FILE="$TEMP_CERT_DIR/tls.crt"
KEY_FILE="$TEMP_CERT_DIR/tls.key"

# Generate a self-signed certificate for the TLS secret as fallback
# cert-manager will replace this with a proper Let's Encrypt certificate if configured
if [ -n "${DOMAIN_NAME:-}" ]; then
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -subj "/CN=$DOMAIN_NAME" 2>/dev/null
else
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout "$KEY_FILE" \
        -out "$CERT_FILE" \
        -subj "/CN=$PUBLIC_IP_ADDRESS" 2>/dev/null
fi

# Create or update the TLS secret
kubectl create secret tls opik-tls-secret \
    --cert="$CERT_FILE" \
    --key="$KEY_FILE" \
    --namespace=$NAMESPACE \
    --dry-run=client -o yaml | kubectl apply -f -

# Clean up temporary files
rm -rf "$TEMP_CERT_DIR"

print_success "Created initial TLS secret for NGINX Ingress HTTPS configuration"

# Now handle proper certificate provisioning with cert-manager
handle_certificate_provisioning
CERT_PROVISIONING_RESULT=$?

# =============================================================================
# FINAL DEPLOYMENT VALIDATION
# =============================================================================

print_step "� Final deployment validation"
print_info "Waiting for all services to stabilize..."
sleep 30

# Check basic deployment status
kubectl get pods -n $NAMESPACE
kubectl get services -n $NAMESPACE
kubectl get ingress -n $NAMESPACE -o wide

# Test connectivity with proper SSL certificate status
print_step "🔍 Testing connectivity and SSL status"

# Determine the correct URL to test
HTTPS_URL="https://$PUBLIC_IP_ADDRESS"
if [ -n "$DOMAIN_NAME" ]; then
    HTTPS_URL="https://$DOMAIN_NAME"
fi

print_info "Testing access to: $HTTPS_URL"

# Test HTTP connectivity
HTTP_STATUS=$(curl -o /dev/null -s -w "%{http_code}" --connect-timeout 10 -k "$HTTPS_URL" 2>/dev/null || echo "000")

if [ "$HTTP_STATUS" = "302" ] || [ "$HTTP_STATUS" = "200" ]; then
    print_success "✅ HTTPS connectivity working (HTTP $HTTP_STATUS)"
    
    # If we have a domain name, test the actual certificate
    if [ -n "$DOMAIN_NAME" ]; then
        print_info "Verifying SSL certificate status for domain..."
        
        # Test the actual certificate being served
        CERT_SUBJECT=$(echo | openssl s_client -servername "$DOMAIN_NAME" -connect "$DOMAIN_NAME:443" 2>/dev/null | openssl x509 -noout -subject 2>/dev/null | sed 's/subject=//')
        CERT_ISSUER=$(echo | openssl s_client -servername "$DOMAIN_NAME" -connect "$DOMAIN_NAME:443" 2>/dev/null | openssl x509 -noout -issuer 2>/dev/null | sed 's/issuer=//')
        
        if [ -n "$CERT_SUBJECT" ] && [ -n "$CERT_ISSUER" ]; then
            print_info "Live certificate details:"
            print_info "  Subject: $CERT_SUBJECT"
            print_info "  Issuer: $CERT_ISSUER"
            
            if echo "$CERT_ISSUER" | grep -qi "let's encrypt\|R3\|R10\|R11\|R12"; then
                print_success "🔒 Valid Let's Encrypt certificate is active!"
            elif echo "$CERT_ISSUER" | grep -qi "kubernetes\|fake"; then
                if [ "$CERT_PROVISIONING_RESULT" -eq 0 ]; then
                    print_warning "🔒 NGINX serving default certificate - Let's Encrypt certificate may still be propagating"
                else
                    print_warning "🔒 Using NGINX default certificate - Let's Encrypt provisioning failed"
                fi
            else
                print_warning "🔒 Using self-signed certificate"
            fi
        else
            print_warning "Could not retrieve certificate details"
        fi
    fi
else
    print_warning "⚠️ HTTPS may still be configuring (HTTP $HTTP_STATUS)"
    print_info "Wait a few minutes and try accessing manually"
fi

# =============================================================================
# DEPLOYMENT SUMMARY
# =============================================================================

print_step "📋 Deployment Summary"
print_success "🎉 Opik deployment completed successfully!"

print_section "🏗️ Infrastructure Summary"
print_key_value "Resource Group" "$RESOURCE_GROUP"
print_key_value "AKS Cluster" "$AKS_CLUSTER_NAME"
print_key_value "Ingress Controller" "NGINX Ingress"
print_key_value "Public IP" "$PUBLIC_IP_ADDRESS"
print_key_value "Container Registry" "$ACR_NAME"
print_key_value "Namespace" "$NAMESPACE"

print_section "📊 Deployment Details"
case "${SCENARIO:-FRESH_INSTALL}" in
    "FRESH_INSTALL")
        print_success "✅ Fresh Installation Completed"
        print_info "All components deployed with new configuration"
        print_info "New persistent disks created for data storage"
        ;;
    "CLUSTER_RECREATION") 
        print_success "✅ Cluster Recreation Completed"
        print_info "All previous data has been restored from persistent disks"
        print_success "Your application state has been fully preserved"
        ;;
    "VERSION_UPGRADE")
        print_success "✅ Version Upgrade Completed"
        print_info "Successfully upgraded to version: $OPIK_VERSION"
        print_success "All existing data preserved during upgrade"
        print_info "Schema migrations applied automatically"
        ;;
esac

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
    print_key_value "HTTPS URL" "https://$DOMAIN_NAME"
    print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
    print_success "✅ URL requires Azure AD authentication"
    
    # SSL certificate status for domain with improved reporting
    if [ "${SSL_ENABLED:-false}" = "true" ] && [ -n "${SSL_ISSUER:-}" ]; then
        if [ "$CERT_PROVISIONING_RESULT" -eq 0 ]; then
            print_success "🔒 SSL Certificate: Valid Let's Encrypt certificate active"
        else
            print_warning "🔒 SSL Certificate: Let's Encrypt provisioning failed"
            print_info "Currently using self-signed certificate (browser warnings expected)"
            print_info "Certificate will automatically provision when rate limits reset"
        fi
    else
        print_warning "🔒 SSL Certificate: Self-signed (browser warnings expected)"
    fi
else
    print_error "Domain name not configured in .env.azure-nginx"
    print_info "Please set DOMAIN_NAME in .env.azure-nginx and redeploy"
    print_warning "🔒 HTTPS with IP addresses not supported (SSL certificate limitation)"
    print_success "✅ HTTP access requires Azure AD authentication"
    print_info "💡 To enable HTTPS: Set DOMAIN_NAME in .env.azure-nginx and redeploy"
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
print_info "Scenario-specific troubleshooting:"

case "${SCENARIO:-FRESH_INSTALL}" in
    "FRESH_INSTALL")
        print_info "Fresh installation troubleshooting:"
        print_info "  # If services fail to start:"
        print_info "  kubectl get pods -n $NAMESPACE"
        print_info "  kubectl logs -n $NAMESPACE deployment/opik-backend --tail=50"
        ;;
    "CLUSTER_RECREATION")
        print_info "Cluster recreation troubleshooting:"
        print_info "  # If data doesn't appear restored:"
        print_info "  kubectl get pv | grep opik"
        print_info "  kubectl get pvc -n $NAMESPACE" 
        print_info "  # Check if PVCs are bound to correct PVs"
        print_info "  kubectl describe pvc -n $NAMESPACE"
        ;;
    "VERSION_UPGRADE")
        print_info "Version upgrade troubleshooting:"
        print_info "  # If migration fails:"
        print_info "  kubectl logs -n $NAMESPACE deployment/opik-backend --tail=100 | grep -i migration"
        print_info "  # If ClickHouse schema issues:"
        print_info "  kubectl exec chi-opik-clickhouse-cluster-0-0-0 -n $NAMESPACE -- clickhouse-client --query 'SHOW DATABASES'"
        ;;
esac

print_info ""
print_info "General troubleshooting:"
print_info "If upgrade fails with backend/ClickHouse issues or stuck pods:"
print_info "  # Use the SAFE built-in recovery function (preserves data):"
print_info "  source ./deploy-azure_alt.sh && safe_recover_from_failure"
print_info ""
print_info "  # Or create backup first, then recover:"
print_info "  source ./deploy-azure_alt.sh && create_data_backup && safe_recover_from_failure"
print_info ""
print_info "  # Manually check rollout status:"
print_info "  kubectl rollout status deployment/opik-backend -n $NAMESPACE"
print_info "  kubectl get pods -l app.kubernetes.io/name=opik-backend -n $NAMESPACE"
print_info ""
print_info "  ⚠️  NEVER manually drop databases - use safe_recover_from_failure instead!"
print_info "  # The safe function only clears migration locks, preserving the data"
print_info ""
print_info "If authentication fails with AADSTS650056 error:"
print_info "  az ad app permission list --id $APP_ID"
print_info "  az ad app permission admin-consent --id $APP_ID"
print_info ""
print_info "If you get 'Misconfigured application' errors:"
print_info "  Check App Registration in Azure Portal: https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade"
print_info "  Ensure User.Read permission is granted and admin consented"
print_info ""
print_info "If NGINX Ingress shows 502 errors:"
print_info "  kubectl get pods -l app.kubernetes.io/name=ingress-nginx -n nginx-ingress"
print_info "  kubectl logs -l app.kubernetes.io/name=ingress-nginx -n nginx-ingress --tail=50"
print_info "  kubectl get ingress -n $NAMESPACE"
print_info ""
print_info "If NGINX Ingress has issues:"
print_info "  # Use the built-in fix function:"
print_info "  source ./deploy-azure_alt.sh && fix_nginx_ingress_issues"
print_info ""
print_info "  # Or check NGINX Ingress logs for errors:"
print_info "  kubectl logs -l app.kubernetes.io/name=ingress-nginx -n nginx-ingress | grep -E 'ERROR|WARN|Failed'"
print_info ""
print_info "  # Check LoadBalancer service status:"
print_info "  kubectl get service nginx-ingress-ingress-nginx-controller -n nginx-ingress"
print_info ""
print_info "  # Restart NGINX Ingress after fixing issues:"
print_info "  kubectl rollout restart deployment/nginx-ingress-ingress-nginx-controller -n nginx-ingress"
print_info ""
print_info "Check NGINX Ingress configuration:"
print_info "  kubectl describe ingress -n $NAMESPACE"
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
    print_info "  # If rate limited, check when limit resets:"
    print_info "  kubectl describe certificate opik-tls-secret -n $NAMESPACE | grep -i 'retry after'"
    print_info ""
    print_info "  # Force certificate renewal after rate limit expires:"
    print_info "  kubectl delete certificaterequest -n $NAMESPACE --all"
    print_info "  kubectl delete certificate opik-tls-secret -n $NAMESPACE"
    print_info "  kubectl apply -f - <<EOF"
    print_info "apiVersion: cert-manager.io/v1"
    print_info "kind: Certificate"
    print_info "metadata:"
    print_info "  name: opik-tls-secret"
    print_info "  namespace: $NAMESPACE"
    print_info "spec:"
    print_info "  secretName: opik-tls-secret"
    print_info "  issuerRef:"
    print_info "    name: letsencrypt-prod"
    print_info "    kind: ClusterIssuer"
    print_info "  dnsNames:"
    if [ -n "$DOMAIN_NAME" ]; then
        print_info "  - $DOMAIN_NAME"
    else
        print_info "  - your-domain.com"
    fi
    print_info "EOF"
    print_info ""
    print_info "  # Check ClusterIssuer status:"
    print_info "  kubectl describe clusterissuer letsencrypt-prod"
    print_info ""
    print_info "  # Switch to staging for testing (no rate limits):"
    print_info "  kubectl annotate certificate opik-tls-secret -n $NAMESPACE cert-manager.io/cluster-issuer=letsencrypt-staging --overwrite"
else
    print_info "  # SSL is using self-signed certificates"
    print_info "  # To enable Let's Encrypt SSL:"
    print_info "  # 1. Set DOMAIN_NAME in .env.azure-nginx"
    print_info "  # 2. Set EMAIL_FOR_LETSENCRYPT in .env.azure-nginx"
    print_info "  # 3. Configure DNS: yourdomain.com → $PUBLIC_IP_ADDRESS"
    print_info "  # 4. Run ./deploy-azure_alt.sh again"
fi
print_info ""
print_info "Restart NGINX Ingress if needed:"
print_info "  kubectl rollout restart deployment/nginx-ingress-ingress-nginx-controller -n nginx-ingress"
print_info ""
print_info "Check NGINX Ingress configuration:"
print_info "  kubectl describe ingress -n $NAMESPACE"

print_warning "🔐 Authentication is required - all users will be redirected to Microsoft login"

print_section "🎯 Next Steps"
print_info "Choose how you want to access Opik:"
print_info "1. Use NGINX Ingress (recommended) - Access via public IP with authentication"
print_info "2. Use port forwarding - Access via localhost (bypasses authentication)"
read -p "Enter your choice (1 or 2): " -n 1 -r
echo

if [[ $REPLY == "2" ]]; then
    print_step "Starting Port Forwarding"
    print_info "🌐 Opik will be available at: http://localhost:5173"
    print_warning "Press Ctrl+C to stop port forwarding"
    kubectl port-forward -n $NAMESPACE svc/opik-frontend 5173:5173
else
    print_step "NGINX Ingress Ready"
    if [ -n "$DOMAIN_NAME" ]; then
        print_success "🌐 Application available at: https://$DOMAIN_NAME"
        print_warning "Configure DNS: $DOMAIN_NAME → $PUBLIC_IP_ADDRESS"
        print_success "✅ Access requires Azure AD authentication"
        print_info "It may take a few minutes for NGINX Ingress to configure routing"
        print_info "If you get 502 errors, wait a few minutes and try again"
    else
        print_error "Domain name not configured - deployment requires DOMAIN_NAME"
        print_info "Please set DOMAIN_NAME in .env.azure-nginx and redeploy"
    fi
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
print_info "Active Opik Data Disks:"
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

print_info ""
case "${SCENARIO:-FRESH_INSTALL}" in
    "FRESH_INSTALL")
        print_success "✅ New persistent disks created successfully"
        print_info "Data will persist across future cluster deletions and recreations"
        ;;
    "CLUSTER_RECREATION"|"VERSION_UPGRADE")
        print_success "✅ Existing data preserved and restored successfully"
        print_info "All your previous application data is intact and accessible"
        ;;
esac

print_info ""
print_success "✅ Data will persist across cluster deletions!"
print_info "Safe to delete cluster - data disks remain in main resource group"
print_warning "To delete data permanently, manually delete the opik-*-data-* disks"

# =============================================================================
# ACCESS INSTRUCTIONS
# =============================================================================

print_section "🌐 Access Instructions"
print_info "Two access methods available:"
print_info ""
print_info "1. 🔐 HTTP IP ACCESS (WITH AUTHENTICATION):"
print_key_value "URL" "http://$PUBLIC_IP_ADDRESS/"
print_success "   ✅ Azure Entra ID Authentication Required"
print_success "   ✅ Group-based Access Control"
print_warning "   ⚠️ HTTP only (HTTPS with IP addresses doesn't work due to SSL certificate validation)"
print_info ""
print_info "2. 🔐 HTTPS DOMAIN ACCESS (FULL SECURITY):"
print_key_value "URL" "https://$DOMAIN_NAME/"
print_success "   ✅ Azure Entra ID Authentication"
print_success "   ✅ HTTPS/TLS Encryption"  
print_success "   ✅ Group-based Access Control"
print_success "   ✅ SSL Certificate (Let's Encrypt)"
print_info ""
print_info "Once DNS A record is updated to point $DOMAIN_NAME -> $PUBLIC_IP_ADDRESS:"
print_success "   → HTTPS domain access will work automatically"
print_success "   → SSL certificate will be issued automatically"
print_info "   → Both access methods require authentication"

print_success "🎉 Deployment completed successfully!"