#!/bin/bash

# =============================================================================
# SSL Setup Script for Opik on Azure Application Gateway
# =============================================================================
# This script follows Microsoft's official Let's Encrypt documentation:
# https://docs.microsoft.com/en-us/azure/application-gateway/ingress-lets-encrypt
#
# Use this script when you need to:
# 1. Add SSL certificates to an existing deployment
# 2. Change domain names
# 3. Switch between staging and production certificates
# =============================================================================

set -euo pipefail

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color
BOLD='\033[1m'

# Print functions
print_header() {
    echo -e "${BLUE}${BOLD}============================================${NC}"
    echo -e "${BLUE}${BOLD} $1${NC}"
    echo -e "${BLUE}${BOLD}============================================${NC}"
}

print_step() {
    echo -e "${GREEN}${BOLD}▶ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ  $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠  $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_key_value() {
    printf "%-25s: %s\n" "$1" "$2"
}

# =============================================================================
# CONFIGURATION
# =============================================================================

print_header "Opik SSL Certificate Setup"

# Check if domain name is provided
if [ $# -eq 0 ]; then
    print_error "Usage: $0 <domain-name> [email] [staging]"
    echo "Examples:"
    echo "  $0 opik.unilabspt.com                                    # Production cert"
    echo "  $0 opik.unilabspt.com user@domain.com                   # Production cert with custom email"
    echo "  $0 opik.unilabspt.com user@domain.com staging           # Staging cert for testing"
    exit 1
fi

DOMAIN_NAME="$1"
EMAIL="${2:-luissampaio.arteiro@unilabspt.com}"  # Default email
USE_STAGING="${3:-}"  # If "staging" is passed, use staging environment

# Determine issuer and certificate names
if [ "$USE_STAGING" = "staging" ]; then
    ISSUER_NAME="letsencrypt-staging"
    CERT_NAME="opik-tls-staging"
    SECRET_NAME="opik-tls-staging"
    print_warning "Using Let's Encrypt STAGING environment (test certificates)"
else
    ISSUER_NAME="letsencrypt-prod"
    CERT_NAME="opik-tls-prod"
    SECRET_NAME="opik-tls-secret"
    print_info "Using Let's Encrypt PRODUCTION environment (trusted certificates)"
fi

NAMESPACE="opik"

print_step "Configuration Summary"
print_key_value "Domain Name" "$DOMAIN_NAME"
print_key_value "Email" "$EMAIL"
print_key_value "Issuer" "$ISSUER_NAME"
print_key_value "Certificate Name" "$CERT_NAME"
print_key_value "Secret Name" "$SECRET_NAME"
print_key_value "Namespace" "$NAMESPACE"

# =============================================================================
# PREREQUISITES CHECK
# =============================================================================

print_step "Checking prerequisites..."

# Check if kubectl is available and connected
if ! kubectl cluster-info >/dev/null 2>&1; then
    print_error "kubectl is not connected to a Kubernetes cluster"
    exit 1
fi

# Check if namespace exists
if ! kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
    print_error "Namespace '$NAMESPACE' does not exist"
    print_info "Make sure Opik is deployed first using deploy-azure.sh"
    exit 1
fi

# Check if cert-manager is installed
if ! kubectl get namespace cert-manager >/dev/null 2>&1; then
    print_error "cert-manager is not installed"
    print_info "Make sure Opik is deployed first using deploy-azure.sh"
    exit 1
fi

print_success "Prerequisites check passed"

# =============================================================================
# CLUSTERISSUER SETUP
# =============================================================================

print_step "Setting up ClusterIssuer..."

# Create or update the ClusterIssuer
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: $ISSUER_NAME
spec:
  acme:
    email: $EMAIL
    server: $([ "$USE_STAGING" = "staging" ] && echo "https://acme-staging-v02.api.letsencrypt.org/directory" || echo "https://acme-v02.api.letsencrypt.org/directory")
    privateKeySecretRef:
      name: $ISSUER_NAME
    solvers:
    - http01:
        ingress:
          class: azure-application-gateway
EOF

print_success "ClusterIssuer '$ISSUER_NAME' configured"

# =============================================================================
# CERTIFICATE CREATION
# =============================================================================

print_step "Creating certificate for $DOMAIN_NAME..."

# Delete existing certificate if it exists
kubectl delete certificate "$CERT_NAME" -n "$NAMESPACE" 2>/dev/null || true
kubectl delete secret "$SECRET_NAME" -n "$NAMESPACE" 2>/dev/null || true

# Create the certificate
cat <<EOF | kubectl apply -f -
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: $CERT_NAME
  namespace: $NAMESPACE
spec:
  secretName: $SECRET_NAME
  dnsNames:
  - $DOMAIN_NAME
  issuerRef:
    name: $ISSUER_NAME
    kind: ClusterIssuer
    group: cert-manager.io
EOF

print_success "Certificate '$CERT_NAME' created"

# =============================================================================
# INGRESS UPDATE
# =============================================================================

print_step "Updating existing ingress with SSL configuration..."

# Check if the main OAuth2 ingress exists
EXISTING_INGRESS=$(kubectl get ingress -n "$NAMESPACE" -o name | grep oauth2 | head -1)
if [ -n "$EXISTING_INGRESS" ]; then
    INGRESS_NAME=$(echo "$EXISTING_INGRESS" | sed 's|ingress/||')
    print_info "Found existing ingress: $INGRESS_NAME"
    
    # Update the existing ingress with SSL configuration
    kubectl patch ingress "$INGRESS_NAME" -n "$NAMESPACE" --type='merge' -p="{
        \"metadata\": {
            \"annotations\": {
                \"cert-manager.io/cluster-issuer\": \"$ISSUER_NAME\"
            }
        },
        \"spec\": {
            \"tls\": [{
                \"hosts\": [\"$DOMAIN_NAME\"],
                \"secretName\": \"$SECRET_NAME\"
            }],
            \"rules\": [{
                \"host\": \"$DOMAIN_NAME\",
                \"http\": {
                    \"paths\": [{
                        \"path\": \"/\",
                        \"pathType\": \"Prefix\",
                        \"backend\": {
                            \"service\": {
                                \"name\": \"opik-oauth2-proxy\",
                                \"port\": {
                                    \"number\": 4180
                                }
                            }
                        }
                    }]
                }
            }]
        }
    }"
    
    print_success "Updated existing ingress '$INGRESS_NAME' with SSL configuration"
else
    print_warning "No existing OAuth2 ingress found. Creating new SSL ingress..."
    
    # Create a new ingress if none exists
    cat <<EOF | kubectl apply -f -
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: opik-ssl-ingress
  namespace: $NAMESPACE
  annotations:
    cert-manager.io/cluster-issuer: $ISSUER_NAME
spec:
  ingressClassName: azure-application-gateway
  tls:
  - hosts:
    - $DOMAIN_NAME
    secretName: $SECRET_NAME
  rules:
  - host: $DOMAIN_NAME
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: opik-oauth2-proxy
            port:
              number: 4180
EOF
    
    print_success "Created new SSL ingress 'opik-ssl-ingress'"
fi

# =============================================================================
# MONITORING CERTIFICATE PROVISIONING
# =============================================================================

print_step "Monitoring certificate provisioning..."

print_info "Waiting for certificate to be issued..."
print_info "This may take 2-5 minutes as Let's Encrypt validates domain ownership"

# Monitor certificate status
for attempt in {1..30}; do
    CERT_STATUS=$(kubectl get certificate "$CERT_NAME" -n "$NAMESPACE" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo "Unknown")
    
    if [ "$CERT_STATUS" = "True" ]; then
        print_success "Certificate successfully issued!"
        break
    elif [ "$CERT_STATUS" = "False" ]; then
        print_warning "Certificate issuance failed. Checking details..."
        kubectl describe certificate "$CERT_NAME" -n "$NAMESPACE"
        break
    else
        print_info "Certificate status: Pending (attempt $attempt/30)"
        if [ $attempt -eq 30 ]; then
            print_warning "Certificate issuance taking longer than expected"
            print_info "Check status manually:"
            print_info "  kubectl describe certificate $CERT_NAME -n $NAMESPACE"
            print_info "  kubectl get challenges -n $NAMESPACE"
        else
            sleep 10
        fi
    fi
done

# =============================================================================
# VERIFICATION
# =============================================================================

print_step "Verifying SSL setup..."

# Check certificate status
print_info "Certificate status:"
kubectl get certificate "$CERT_NAME" -n "$NAMESPACE" -o wide

# Check ingress status
print_info "Ingress status:"
kubectl get ingress opik-ssl-ingress -n "$NAMESPACE" -o wide

# Test HTTPS connectivity
print_info "Testing HTTPS connectivity..."
if curl -k -s -o /dev/null -w "%{http_code}" "https://$DOMAIN_NAME" | grep -q "200\|302\|301"; then
    print_success "HTTPS endpoint is responding"
else
    print_warning "HTTPS endpoint may not be ready yet"
fi

# =============================================================================
# COMPLETION SUMMARY
# =============================================================================

print_header "SSL Setup Complete"

if [ "$USE_STAGING" = "staging" ]; then
    print_warning "🧪 STAGING CERTIFICATES CONFIGURED"
    print_info "The certificate is from Let's Encrypt staging environment (not trusted by browsers)"
    print_info "To get production certificates, run:"
    print_info "  $0 $DOMAIN_NAME $EMAIL"
else
    print_success "🔐 PRODUCTION CERTIFICATES CONFIGURED"
    print_info "Your site should now have trusted SSL certificates"
fi

print_info ""
print_info "🌐 Access your application at: https://$DOMAIN_NAME"
print_info ""
print_info "🔍 Troubleshooting commands:"
print_info "  kubectl describe certificate $CERT_NAME -n $NAMESPACE"
print_info "  kubectl get challenges -n $NAMESPACE"
print_info "  kubectl describe clusterissuer $ISSUER_NAME"
print_info "  kubectl logs -l app=cert-manager -n cert-manager"

print_success "SSL setup completed successfully!"
