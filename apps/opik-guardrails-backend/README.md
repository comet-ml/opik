# Opik Guardrails Backend

This is the backend service for Opik Guardrails.

## Running with Docker

```bash
# Option 1: Pull and run the pre-built image
docker run -p 5000:5000 ghcr.io/comet-ml/opik/opik-guardrails-backend:latest

# Option 2: Build locally (only needed once)
cd apps/opik-guardrails-backend
docker build -t opik-guardrails-backend:latest .

# Option 3: Run a locally built image
docker run -p 5000:5000 opik-guardrails-backend:latest
```

The server will be available at http://localhost:5000
