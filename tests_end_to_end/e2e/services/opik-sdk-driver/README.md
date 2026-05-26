# opik-sdk-driver

Lean FastAPI bridge that wraps the Opik Python SDK so the E2E Playwright suite
(written in TypeScript) can drive user-flow state through the real SDK code
path. Test setup uses the same SDK surface users use, so SDK regressions
surface as test failures.

This service is consumed by the TypeScript clients shipped in OPIK-6499 and
wired into Playwright via `webServer` in OPIK-6500. It is not invoked directly
by any test in this ticket.

See [OPIK-6106](https://comet-ml.atlassian.net/browse/OPIK-6106) for the
broader Opik 2.0 E2E infrastructure design that motivates this service.

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

`uv.lock` is intentionally not checked in — the bridge's job is to surface
Opik SDK regressions, so every `uv sync` resolves against PyPI for the
newest matching version of `opik`. Run `uv sync --upgrade` if you want to
refresh an existing `.venv/` to the latest SDK.

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

Errors — the bridge catches `opik.rest_api.core.api_error.ApiError` and
re-raises it as `HTTPException` with the SDK's original status code and
body, so backend errors stay faithful to the wire:

- `422` — missing required field (`name`), via Pydantic. Response body lists
  the missing field path.
- `409` — duplicate name. Body: `{"detail": {"errors": ["Project already exists"]}}`.
- Other 4xx — propagated from the backend (auth, permissions, etc.) with
  the SDK's `body` under `detail`.
- `500` — uncaught non-API errors (network failures, programmer bugs). The
  stack trace is in the bridge's stderr.

## Adding a new route

1. Add the request/response Pydantic models to `src/opik_sdk_driver/schemas.py`.
2. Create `src/opik_sdk_driver/routes/<entity>.py` with an
   `APIRouter(prefix="/<entity>", tags=["<entity>"])` and the SDK-driven
   handler.
3. Register it in `src/opik_sdk_driver/main.py` via `app.include_router`.

Follow the pattern in `routes/projects.py`:

- Instantiate `opik.Opik()` fresh per request so workspaces don't leak.
- Wrap SDK calls in `try/except ApiError`, re-raise as `HTTPException` with
  the SDK's `status_code` and `body` so callers see real backend errors.
- In a `finally` block, `client.end(flush=False)` + `atexit.unregister(client.end)`
  to avoid leaking the streamer thread and atexit reference the SDK
  constructor creates.
