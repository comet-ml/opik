# Plan: Support RPi4/CM4 Emulation Compatibility for Opik

## Architectural Design
Expose the Docker Compose `platform` property for all architecture-sensitive services using interpolation variables:
* `${OPIK_CLICKHOUSE_PLATFORM:-}`
* `${OPIK_BACKEND_PLATFORM:-}`
* `${OPIK_PYTHON_BACKEND_PLATFORM:-}`
* `${OPIK_GUARDRAILS_BACKEND_PLATFORM:-}`

By using `${VAR:-}`, Docker Compose falls back to an unset/empty string value if the variable is not defined in the host shell environment, defaulting to the native architecture of the host (standard Docker Compose behavior).

## Proposed File Changes
* **[MODIFY]** [docker-compose.yaml](file:///C:/Users/LENOVO/.gemini/antigravity/scratch/opik/deployment/docker-compose/docker-compose.yaml)
  * Parameterize `platform` on `guardrails-backend`.
  * Parameterize `platform` on `demo-data-generator` (reusing `OPIK_PYTHON_BACKEND_PLATFORM`).
* **[MODIFY]** [README.md](file:///C:/Users/LENOVO/.gemini/antigravity/scratch/opik/deployment/docker-compose/README.md)
  * Add QEMU prerequisite command: `docker run --privileged --rm tonistiigi/binfmt --install amd64`.
  * Update instructions for running the RPi environment variables.

## Verification Plan
### Automated Verification
* Run `docker compose config` to verify the generated configuration behaves correctly:
  * Case A: No env variables set -> `platform` resolves to unset/empty.
  * Case B: Env variables set -> `platform` resolves to `linux/amd64` correctly.
