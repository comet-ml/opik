# LLM API Keys Configuration for Optimization Studio

This document explains how to configure LLM API keys for the Optimization Studio feature across different deployment scenarios.

## Overview

The Optimization Studio requires LLM API keys to run optimization jobs. The Python backend supports three provider keys:

- **`OPENAI_API_KEY`** - For OpenAI models (GPT-4, GPT-3.5, etc.)
- **`ANTHROPIC_API_KEY`** - For Anthropic models (Claude, etc.)
- **`OPENROUTER_API_KEY`** - For OpenRouter (multi-provider access)

These keys are **optional** but required for running optimization jobs. If missing, the worker will log a warning at job start but won't fail immediately.

## Configuration Methods

Choose the method that best fits your deployment scenario:

### 1. Local Development (Environment Variables)

**Use case:** Running Opik locally with `./opik.sh` or `scripts/dev-runner.sh`

**Setup:**

```bash
# Export keys in your shell before starting Opik
export OPENAI_API_KEY="sk-..."
export ANTHROPIC_API_KEY="sk-ant-..."
export OPENROUTER_API_KEY="sk-or-..."

# Then start Opik
./opik.sh
```

**How it works:**
- Docker Compose automatically passes environment variables from the host shell
- Keys are available to the python-backend container at runtime

**Pros:**
- ✅ Simple and quick for development
- ✅ No files to manage
- ✅ Easy to rotate keys

**Cons:**
- ❌ Must export in every new shell session
- ❌ Not suitable for production

---

### 2. Docker Compose Override (Local "Production")

**Use case:** Running Opik with Docker Compose for testing or small deployments

**Setup:**

Create a `docker-compose.override.yaml` file in `deployment/docker-compose/`:

```yaml
# deployment/docker-compose/docker-compose.override.yaml
services:
  python-backend:
    environment:
      OPENAI_API_KEY: "sk-..."
      ANTHROPIC_API_KEY: "sk-ant-..."
      OPENROUTER_API_KEY: "sk-or-..."
      # Optionally add AWS credentials if using S3
      AWS_ACCESS_KEY_ID: "AKIA..."
      AWS_SECRET_ACCESS_KEY: "..."
```

Then start Opik:

```bash
cd deployment/docker-compose
docker compose up -d
```

**How it works:**
- Docker Compose merges `docker-compose.yaml` with `docker-compose.override.yaml`
- Override file is git-ignored for security
- Environment variables are injected into containers

**Pros:**
- ✅ Persistent across restarts
- ✅ No shell exports needed
- ✅ Git-ignored for security

**Cons:**
- ❌ Keys stored in plaintext file
- ❌ Manual file management

**Security Note:** Add `docker-compose.override.yaml` to `.gitignore` to prevent committing secrets!

---

### 3. Kubernetes Secrets (Production)

**Use case:** Running Opik in Kubernetes with Helm

The Helm chart supports **optional** `secretRefs` for the python-backend component. You can use:
- **ExternalSecret Operator** (recommended for AWS/GCP/Azure)
- **Kubernetes Secrets CSI Driver**
- **Manual Kubernetes Secrets**

#### Option A: Using ExternalSecret Operator (Recommended)

**Setup with AWS Secrets Manager:**

1. **Create secrets in AWS Secrets Manager:**

```bash
# Create secret for OpenAI
aws secretsmanager create-secret \
  --name opik/llm-api-keys \
  --secret-string '{
    "OPENAI_API_KEY": "sk-...",
    "ANTHROPIC_API_KEY": "sk-ant-...",
    "OPENROUTER_API_KEY": "sk-or-..."
  }'
```

2. **Create ExternalSecret in Kubernetes:**

```yaml
# externalsecret.yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: opik-llm-api-keys
  namespace: opik
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-secrets-manager  # Your SecretStore name
    kind: SecretStore
  target:
    name: opik-llm-api-keys  # Name of K8s secret to create
    creationPolicy: Owner
  data:
    - secretKey: OPENAI_API_KEY
      remoteRef:
        key: opik/llm-api-keys
        property: OPENAI_API_KEY
    - secretKey: ANTHROPIC_API_KEY
      remoteRef:
        key: opik/llm-api-keys
        property: ANTHROPIC_API_KEY
    - secretKey: OPENROUTER_API_KEY
      remoteRef:
        key: opik/llm-api-keys
        property: OPENROUTER_API_KEY
```

3. **Apply the ExternalSecret:**

```bash
kubectl apply -f externalsecret.yaml
```

4. **Reference the secret in Helm values:**

```yaml
# values.yaml or values-override.yaml
component:
  python-backend:
    secretRefs:
      - name: "opik-llm-api-keys"
```

5. **Install/upgrade Opik:**

```bash
helm upgrade --install opik ./deployment/helm_chart/opik \
  -f values-override.yaml \
  --namespace opik
```

#### Option B: Using Manual Kubernetes Secrets

**Setup:**

1. **Create Kubernetes secret:**

```bash
kubectl create secret generic opik-llm-api-keys \
  --from-literal=OPENAI_API_KEY="sk-..." \
  --from-literal=ANTHROPIC_API_KEY="sk-ant-..." \
  --from-literal=OPENROUTER_API_KEY="sk-or-..." \
  --namespace opik
```

2. **Reference the secret in Helm values:**

```yaml
# values.yaml or values-override.yaml
component:
  python-backend:
    secretRefs:
      - name: "opik-llm-api-keys"
```

3. **Install/upgrade Opik:**

```bash
helm upgrade --install opik ./deployment/helm_chart/opik \
  -f values-override.yaml \
  --namespace opik
```

#### Multiple Secrets Support

You can reference multiple secrets for better organization:

```yaml
component:
  python-backend:
    secretRefs:
      - name: "opik-llm-api-keys"      # LLM provider keys
      - name: "opik-aws-credentials"    # AWS S3 credentials
      - name: "opik-additional-config"  # Other secrets
```

**How it works:**
- Helm generates `envFrom` entries for each `secretRef`
- Kubernetes injects all key-value pairs from each secret as environment variables
- ExternalSecret Operator syncs secrets from external providers (AWS/GCP/Azure)

**Pros:**
- ✅ Production-ready security
- ✅ Automatic secret rotation (with ExternalSecret)
- ✅ Centralized secret management
- ✅ RBAC and audit logs
- ✅ No secrets in Helm values or Git

**Cons:**
- ❌ Requires additional infrastructure (ExternalSecret Operator, SecretStore)
- ❌ More complex setup

---

## Verification

### Check if keys are loaded

**Docker Compose:**

```bash
docker compose -f deployment/docker-compose/docker-compose.yaml exec python-backend env | grep API_KEY
```

**Kubernetes:**

```bash
kubectl exec -n opik deployment/python-backend -c python-backend -- env | grep API_KEY
```

### Check Python backend logs

Look for these messages on startup:

```
[INFO] LLM API Keys configured: OPENAI_API_KEY, ANTHROPIC_API_KEY
[WARNING] Missing LLM API keys: OPENROUTER_API_KEY
```

Or when a job starts:

```
[INFO] Processing Optimization Studio job: <id> for workspace: <name>
[INFO] Using model: gpt-4 with params: {...}
```

---

## Security Best Practices

### For All Environments

1. **Never commit secrets to Git**
   - Use `.gitignore` for override files
   - Use ExternalSecret for Kubernetes

2. **Rotate keys regularly**
   - Set up key rotation policies
   - Update secrets when team members leave

3. **Use minimum required permissions**
   - Create API keys with limited scopes
   - Use separate keys per environment

### For Kubernetes

1. **Use ExternalSecret Operator**
   - Centralize secret management in AWS/GCP/Azure
   - Enable automatic rotation
   - Audit secret access

2. **Enable RBAC**
   ```yaml
   apiVersion: rbac.authorization.k8s.io/v1
   kind: Role
   metadata:
     name: opik-secret-reader
   rules:
   - apiGroups: [""]
     resources: ["secrets"]
     resourceNames: ["opik-llm-api-keys"]
     verbs: ["get"]
   ```

3. **Encrypt secrets at rest**
   - Enable encryption in your Kubernetes cluster
   - Use cloud provider KMS (AWS KMS, GCP Cloud KMS, etc.)

---

## Troubleshooting

### Keys not recognized by LiteLLM

**Symptom:** Jobs fail with authentication errors

**Solution:**
- Verify key format matches provider requirements
- Check keys are exported/mounted correctly
- Ensure no extra whitespace or quotes

### AWS credentials not working

**Symptom:** S3 operations fail with permission errors

**Solution:**
- Add `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`
- Verify IAM permissions for S3 bucket access
- Check bucket name and region

### ExternalSecret not syncing

**Symptom:** Kubernetes secret not created

**Solution:**
```bash
# Check ExternalSecret status
kubectl describe externalsecret opik-llm-api-keys -n opik

# Check SecretStore configuration
kubectl describe secretstore aws-secrets-manager -n opik

# Check operator logs
kubectl logs -n external-secrets-system deployment/external-secrets
```

---

## Configuration Summary

| Method | Use Case | Security | Complexity | Rotation |
|--------|----------|----------|------------|----------|
| **Environment Variables** | Development | ⚠️ Low | ✅ Simple | Manual |
| **Docker Override** | Testing/Small Deploy | ⚠️ Medium | ✅ Simple | Manual |
| **Kubernetes Secrets** | Production | ✅ High | ⚠️ Complex | Automatic* |

\* Automatic with ExternalSecret Operator

---

## Example Helm Values

```yaml
# Production values with ExternalSecret
component:
  python-backend:
    enabled: true
    replicaCount: 3
    
    # Reference secrets created by ExternalSecret
    secretRefs:
      - name: "opik-llm-api-keys"
      - name: "opik-aws-credentials"
    
    # Other python-backend config
    env:
      OPIK_URL_OVERRIDE: "https://opik.example.com"
      PYTHON_CODE_EXECUTOR_STRATEGY: "docker"
```

---

## References

- [ExternalSecret Operator Documentation](https://external-secrets.io/)
- [AWS Secrets Manager](https://docs.aws.amazon.com/secretsmanager/)
- [Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)
- [LiteLLM Environment Variables](https://docs.litellm.ai/docs/providers)

