# Walkthrough: Support RPi4/CM4 Emulation Compatibility for Opik

## Changes Made
* **Docker Compose Configuration**: Added the platform parameterization variable to the remaining backend services:
  * `demo-data-generator` using `${OPIK_PYTHON_BACKEND_PLATFORM:-}`
  * `guardrails-backend` using `${OPIK_GUARDRAILS_BACKEND_PLATFORM:-}`
* **Documentation**: Updated the `deployment/docker-compose/README.md` to specify:
  * Prerequisite QEMU registration command (`docker run --privileged --rm tonistiigi/binfmt --install amd64`).
  * Softened RPi compatibility to a "best-effort workaround".
  * Warned about ClickHouse/JVM resource limitations on 4GB RPi4.
* **SDD Artifacts**: Added `spec.md`, `plan.md`, `task.md` under `.specify/`.

## Verification Results
Executed the Python PyYAML validation script [verify_compose_yaml.py](file:///C:/Users/LENOVO/.gemini/antigravity/scratch/opik/verify_compose_yaml.py):
```text
=== Verifying deployment/docker-compose/docker-compose.yaml syntax and values ===
demo-data-generator platform: ${OPIK_PYTHON_BACKEND_PLATFORM:-}
guardrails-backend platform: ${OPIK_GUARDRAILS_BACKEND_PLATFORM:-}

Verification Successful! YAML is syntactically valid and platform properties are correct.
```

All acceptance criteria are fully met.
