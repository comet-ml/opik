---
name: local-dev
description: Local development environment setup and commands. Use when helping with dev server, Docker, or local testing.
---

# Local Development

## Quick Start

```bash
./scripts/dev-runner.sh --restart   # First time / full rebuild
./scripts/dev-runner.sh --start     # Daily start (no rebuild)
./scripts/dev-runner.sh --stop      # Stop everything
./scripts/dev-runner.sh --verify    # Check status
```

## Modes

| Mode | Command | Frontend | Use When |
|------|---------|----------|----------|
| Standard | `--restart` | localhost:5174 | Frontend work, full-stack |
| BE-only | `--be-only-restart` | localhost:5173 | Backend-focused, faster rebuilds |

## URLs

- **Frontend**: localhost:5174 (standard) / localhost:5173 (BE-only)
- **Backend API**: localhost:8080
- **Health**: http://localhost:8080/health-check?name=all

## Build Commands

```bash
./scripts/dev-runner.sh --build-be   # Backend only
./scripts/dev-runner.sh --build-fe   # Frontend only
./scripts/dev-runner.sh --lint-be    # Spotless
./scripts/dev-runner.sh --lint-fe    # ESLint
./scripts/dev-runner.sh --migrate    # DB migrations
```

## Logs

```bash
tail -f /tmp/opik-backend.log        # Backend
tail -f /tmp/opik-frontend.log       # Frontend (standard)
docker logs -f opik-frontend-1       # Frontend (BE-only)
```

## SDK Config

```bash
export OPIK_URL_OVERRIDE='http://localhost:8080'
export OPIK_WORKSPACE='default'
```

## Troubleshooting

**Won't start:**
```bash
./scripts/dev-runner.sh --verify
lsof -i :8080                        # Port conflict?
./scripts/dev-runner.sh --stop && ./scripts/dev-runner.sh --restart
```

**Build fails:**
```bash
cd apps/opik-backend && mvn clean install -DskipTests
cd apps/opik-frontend && rm -rf node_modules && npm install
```

**Database issues:**
```bash
./scripts/dev-runner.sh --stop
./opik.sh --clean                    # WARNING: deletes data
./scripts/dev-runner.sh --restart
```
