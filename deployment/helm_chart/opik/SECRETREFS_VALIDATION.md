# SecretRefs Validation Guide

This document explains how to validate the `secretRefs` feature in the Helm chart.

## How It Works

The `secretRefs` configuration in `values.yaml` allows you to reference existing Kubernetes secrets:

```yaml
component:
  python-backend:
    secretRefs:
      - name: "opik-llm-api-keys"
      - name: "opik-aws-credentials"
```

This generates the following in the Deployment YAML:

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
      - name: python-backend
        envFrom:
          # Existing configMap
          - configMapRef:
              name: opik-python-backend
          # New: secretRefs are appended
          - secretRef:
              name: opik-llm-api-keys
          - secretRef:
              name: opik-aws-credentials
```

## Template Logic

The template in `templates/deployment.yaml` (lines 197-204):

```yaml
{{- if $value.secretRefs }}
{{- range $value.secretRefs }}
{{- if .name }}
  - secretRef:
      name: {{ .name }}
{{- end }}
{{- end }}
{{- end }}
```

**How it works:**
1. Checks if `secretRefs` is defined and non-empty
2. Iterates over each entry in `secretRefs`
3. For each entry with a `name` field, creates a `secretRef` entry
4. All entries are appended to the existing `envFrom` list

## Manual Validation

Since Helm dependency build may fail due to certificate issues, you can manually validate:

### 1. Check Template Syntax

```bash
# View the template logic
grep -A 10 "secretRefs" deployment/helm_chart/opik/templates/deployment.yaml
```

### 2. Verify Values Structure

```bash
# View the values configuration
grep -A 20 "secretRefs" deployment/helm_chart/opik/values.yaml
```

### 3. Expected Output

With this configuration:

```yaml
component:
  python-backend:
    secretRefs:
      - name: "test-secret-1"
      - name: "test-secret-2"
    envFrom:
      - configMapRef:
          name: opik-python-backend
```

The rendered deployment should have:

```yaml
envFrom:
  - configMapRef:
      name: opik-python-backend
  - secretRef:
      name: test-secret-1
  - secretRef:
      name: test-secret-2
```

## Testing in Real Cluster

### 1. Create test secrets

```bash
kubectl create secret generic test-secret-1 \
  --from-literal=TEST_KEY_1="value1" \
  --namespace opik

kubectl create secret generic test-secret-2 \
  --from-literal=TEST_KEY_2="value2" \
  --namespace opik
```

### 2. Deploy with secretRefs

```yaml
# values-test.yaml
component:
  python-backend:
    secretRefs:
      - name: "test-secret-1"
      - name: "test-secret-2"
```

```bash
helm upgrade --install opik ./deployment/helm_chart/opik \
  -f values-test.yaml \
  --namespace opik
```

### 3. Verify environment variables

```bash
kubectl exec -n opik deployment/python-backend -c python-backend -- env | grep TEST_KEY
```

Expected output:
```
TEST_KEY_1=value1
TEST_KEY_2=value2
```

## Edge Cases Handled

1. **Empty secretRefs list**: `secretRefs: []` → No secretRefs added (works correctly)
2. **Undefined secretRefs**: No `secretRefs` key → Template skips the block (works correctly)
3. **Entry without name**: `- name: ""` → Skipped due to `{{- if .name }}` check
4. **Multiple secrets**: All entries are rendered as separate `- secretRef` items

## Security Notes

- **Secrets must exist** before deploying the Helm chart
- **RBAC required**: ServiceAccount must have `get` permission on secrets
- **No validation**: Helm doesn't validate if secrets exist at template time
- **Startup failure**: Pod will fail if referenced secret doesn't exist

## Validation Checklist

- [x] Template syntax is correct (lines 197-204)
- [x] Values.yaml has descriptive comments
- [x] Documentation exists (LLM_API_KEYS_CONFIGURATION.md)
- [x] Edge cases handled (empty list, missing name)
- [x] Appends to existing envFrom (doesn't replace)
- [x] Works with any secret name
- [x] Optional (secretRefs: [] is valid)

## Related Files

- `values.yaml` (lines 193-212): Configuration definition
- `templates/deployment.yaml` (lines 197-204): Template logic
- `apps/opik-python-backend/docs/LLM_API_KEYS_CONFIGURATION.md`: Usage guide

