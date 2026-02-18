# Apps

`apps/` contains deployable Opik services and product surfaces.

## Module map
- `opik-backend`: Java backend API and persistence services.
- `opik-frontend`: React application for the Opik UI.
- `opik-documentation`: Documentation site and guides.
- `opik-ai-backend`: AI-facing backend helpers/integrations.
- `opik-guardrails-backend`: Guardrails service integration.
- `opik-python-backend`: Python runtime/backend tooling.
- `opik-sandbox-executor-python`: Sandbox execution service for tests or eval flows.

## Common contributor flow
- Run full stack: `./opik.sh --build`, stop with `./opik.sh --stop`.
- Fast dev mode: `scripts/dev-runner.sh` (FE + BE), or `scripts/dev-runner.sh --be-only-restart`.
- Backend checks: `scripts/dev-runner.sh --lint-be`.
- Frontend checks: `scripts/dev-runner.sh --lint-fe`.

## Per-app entry points
- `apps/opik-backend/README.md`
- `apps/opik-frontend/README.md`
- `apps/opik-documentation/README.md` (if present per area)
- `apps/opik-python-backend/`, `apps/opik-guardrails-backend/`, and `apps/opik-sandbox-executor-python/` also include their own docs.

## Test locations
- Backend: `apps/opik-backend/src/test/java`.
- Frontend: repository root scripts and `apps/opik-frontend` test/lint configuration.
- E2E and installer workflows: `tests_end_to_end/` and `.github/workflows/`.
