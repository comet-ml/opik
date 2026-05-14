# opik-sdk-driver

Lean FastAPI bridge that wraps the Opik Python SDK so the E2E Playwright suite
(written in TypeScript) can drive user-flow state through the real SDK code
path. Test setup uses the same SDK surface users use, so SDK regressions
surface as test failures.

This service is consumed by the TypeScript clients shipped in OPIK-6499 and
wired into Playwright via `webServer` in OPIK-6500. It is not invoked directly
by any test in this ticket.

See `docs/superpowers/specs/2026-04-23-opik-2.0-e2e-infrastructure-design.md`
§7 for the broader architecture rationale.

## Run locally

```bash
cd tests_end_to_end/e2e/services/opik-sdk-driver
uv sync
OPIK_API_KEY=<your key> \
  OPIK_URL_OVERRIDE=https://staging.dev.comet.com/opik/api \
  OPIK_WORKSPACE=<your workspace> \
  uv run uvicorn opik_sdk_driver.main:app --port 5175
```

Then:
- `GET http://localhost:5175/health` → `{"status":"ok"}`
- `GET http://localhost:5175/docs` → Swagger UI (auto-generated)
- `GET http://localhost:5175/openapi.json` → OpenAPI 3 spec

If `uv run uvicorn` raises `ModuleNotFoundError: No module named 'opik_sdk_driver'`,
the editable install drifted. Run `uv sync --reinstall` and retry.

## Environment

The Python SDK reads these env vars automatically when `opik.Opik()` is
constructed:

| var | purpose |
|---|---|
| `OPIK_API_KEY` | bearer token for the Opik backend |
| `OPIK_URL_OVERRIDE` | Opik API base URL (e.g., staging) |
| `OPIK_WORKSPACE` | default workspace when a request omits one |

A request body's optional `workspace` field overrides only the workspace — the
API key and URL stay fixed per bridge process. This prevents a test from
accidentally targeting a different Opik instance.

## Endpoints

### `POST /projects`

Body:
```json
{ "name": "cuj-...", "workspace": "andreicautisanu" }
```

Response (HTTP 201):
```json
{ "id": "<uuid>", "name": "cuj-..." }
```

Errors:
- `422` — missing required field (`name`), via Pydantic. Response body lists
  the missing field path.
- `500` with body `Internal Server Error` — any SDK exception propagates as
  an uncaught error. Duplicate-name is the common case: the Opik backend
  returns 409 with `{"errors":["Project already exists"]}`, the Python SDK
  raises `opik.rest_api.core.api_error.ApiError`, and FastAPI emits a 500
  with no JSON body. **The exception detail is only available in the
  bridge's stderr**, so log capture is required for debugging.

## Adding a new route

1. Add the request/response Pydantic models to `src/opik_sdk_driver/schemas.py`.
2. Create `src/opik_sdk_driver/routes/<entity>.py` with an
   `APIRouter(prefix="/<entity>", tags=["<entity>"])` and the SDK-driven
   handler.
3. Register it in `src/opik_sdk_driver/main.py` via `app.include_router`.

Follow the pattern in `routes/projects.py` — instantiate `opik.Opik()` fresh
per request so workspaces don't leak across calls, then `client.end(flush=False)`
+ `atexit.unregister(client.end)` in a `finally` block to avoid leaking the
streamer thread and atexit reference the SDK constructor creates.
