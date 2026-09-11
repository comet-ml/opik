# Optimization Studio — Onboarding & Design

> Scope: how Optimization Studio works end-to-end, from both a **logical**
> (request lifecycle, responsibilities) and an **infrastructural / architectural**
> (services, isolation, deployment config) perspective.

## 1. What Optimization Studio is

Optimization Studio is the server-side feature that lets a user iteratively
optimize an LLM prompt against a dataset and a set of metrics, using one of
several optimization algorithms (e.g. GEPA, hierarchical). The user kicks it off
from the Opik UI; it runs asynchronously on the backend and streams progress,
logs, and a final optimized prompt back to the UI.

It spans three codebases:

| Layer | Location | Role |
|-------|----------|------|
| Orchestration API | `apps/opik-backend` (Java) | Receives the UI request, enqueues the job, exposes the LLM **gateway** |
| Job runner | `apps/opik-python-backend` (Python) | Consumes the job from Redis, runs each optimization in an isolated subprocess, streams logs |
| Optimization algorithms | `sdks/opik_optimizer` (Python SDK) | The actual optimizers (`optimize_prompt`), used by the runner subprocess |

The "Studio backend" proper lives in
[apps/opik-python-backend/src/opik_backend/studio/](../src/opik_backend/studio/)
and [apps/opik-python-backend/src/opik_backend/jobs/](../src/opik_backend/jobs/).

---

## 2. Logical view — the request lifecycle

```
UI ──HTTP──▶ Java backend ──RQ/Redis──▶ python-backend (RQ worker)
                                              │
                                              ▼
                                  IsolatedSubprocessExecutor
                                              │  (spawns one subprocess per job)
                                              ▼
                                     optimizer_runner.py
                                              │  uses
                                              ▼
                                  opik_optimizer SDK (optimize_prompt)
                                              │  every LLM call via LiteLLM
                                              ▼
                            OPENAI_API_BASE = {OPIK_URL}/v1/private
                                              │
                          ┌───────────────────┘
                          ▼
        Java backend  POST /v1/private/chat/completions   ◀── the gateway
                          │  resolves the workspace's provider key
                          ▼
                  Provider API (OpenAI / Anthropic / OpenRouter / Vertex …)
```

Step by step:

1. **Enqueue.** The Java backend serializes the optimization request into a job
   message and pushes it onto a Redis Queue (RQ). The message shape is documented
   in
   [jobs/optimizer.py:76-89](../src/opik_backend/jobs/optimizer.py#L76-L89):
   `optimization_id`, `workspace_id`, `workspace_name`, a `config` block
   (dataset, prompt, `llm_model`, `evaluation` metrics, `optimizer` type), and an
   optional `opik_api_key` (cloud only).

2. **Consume.** The python-backend runs an RQ worker (`rq_worker.py`) whose job
   handler is
   [`process_optimizer_job`](../src/opik_backend/jobs/optimizer.py#L62). It
   parses the message into an `OptimizationJobContext`.

3. **Prepare the subprocess environment.** In
   [jobs/optimizer.py:117-132](../src/opik_backend/jobs/optimizer.py#L117-L132)
   the worker assembles the env vars the subprocess will run with — this is the
   crux for OPIK-6924 (see §5):
   - `OPENAI_API_BASE` → `OPIK_GATEWAY_BASE_URL` (the gateway), so LiteLLM treats
     the Opik backend as an OpenAI-compatible endpoint.
   - `OPENAI_API_KEY` → the workspace's `opik_api_key`, or the literal
     `"opik-local"` placeholder for self-hosted. **This is not a provider key** —
     it satisfies LiteLLM's "credentials required" check; real auth is the
     workspace header (below).
   - `OPIK_OPTIMIZATION_STUDIO="true"`, `OPIK_API_KEY` (cloud), `OPIK_WORKSPACE`.

4. **Run in isolation.** `IsolatedSubprocessExecutor`
   ([executor_isolated.py](../src/opik_backend/executor_isolated.py)) spawns
   `optimizer_runner.py` as a fresh `subprocess.Popen`, passing the job message
   via stdin and the env vars above. One subprocess per optimization gives:
   customer/API-key isolation, memory isolation (memory cap via `preexec_fn`),
   and crash isolation.

5. **Optimize + route LLM calls.** Inside the subprocess
   ([optimizer_runner.py](../src/opik_backend/jobs/optimizer_runner.py)):
   - [`route_litellm_calls_through_gateway`](../src/opik_backend/jobs/optimizer_runner.py#L131)
     monkey-patches `litellm.completion`/`acompletion` to inject the
     `Comet-Workspace` header on every call. Without it the gateway returns
     `403 "Workspace name should be provided"`.
   - The model string is prefixed with `openai/`
     ([optimizer_runner.py:242-243](../src/opik_backend/jobs/optimizer_runner.py#L242-L243))
     so LiteLLM uses its OpenAI handler (the only one that honors
     `OPENAI_API_BASE`). LiteLLM strips the prefix before the HTTP call, so the
     gateway still receives the original provider-qualified model
     (e.g. `vertex_ai/gemini-2.5-flash`).
   - The optimizer (`opik_optimizer`) runs `optimize_prompt`; status transitions
     and the final result are written back via the Opik SDK from inside the
     subprocess.

6. **Gateway resolves the provider.** The Java backend's
   `POST /v1/private/chat/completions` (`ChatCompletionsResource`) authenticates
   the request by workspace, looks up the **workspace-stored** provider API key
   (managed in the UI under "AI Providers", encrypted in the DB), and forwards to
   the real provider. This is the same path the Playground uses.

7. **Logs & lifecycle.** Subprocess stdout/stderr is streamed to Redis by a
   `RedisBatchLogCollector`
   ([subprocess_logger.py](../src/opik_backend/subprocess_logger.py)) under
   `opik:logs:{workspace_id}:{optimization_id}`, with a TTL, for the UI to tail.
   Cancellation is handled by a `CancellationHandle` that kills the subprocess.

### Why the gateway indirection matters (OPIK-6652)

Before OPIK-6652, the subprocess called provider APIs **directly**, so the
deployment had to inject `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` /
`OPENROUTER_API_KEY` as env vars into the python-backend container. After
OPIK-6652, all calls go through the gateway and **provider keys are resolved
server-side from workspace settings** — the subprocess only needs to reach the
gateway and identify its workspace, so no provider API keys need to be injected
into the deployment at all.

---

## 3. Infrastructural / architectural view

### Services & dependencies

```
┌────────────┐     HTTP      ┌──────────────┐   enqueue   ┌─────────┐
│  frontend  │ ───────────▶  │ backend (Java)│ ─────────▶ │  redis  │
└────────────┘               └──────┬────────┘            └────┬────┘
                                    │  ▲ gateway                │ RQ
                                    │  │ /v1/private            ▼ dequeue
                                    │  └──────────── ┌──────────────────┐
                                    │                │ python-backend   │
                                    │                │  (RQ worker)     │
                                    │                │   └─ subprocess  │
                                    │                │      per job     │
                                    └────────────────┴──────────────────┘
                       (subprocess's LLM calls loop back into the gateway)
```

- **redis** — RQ job queue + log streaming buffer (`REDIS_URL`).
- **backend (Java)** — enqueues jobs; hosts the LLM gateway; stores encrypted
  per-workspace provider keys.
- **python-backend** — runs the RQ worker (`RQ_WORKER_ENABLED`), spawns one
  isolated subprocess per optimization. Runs `privileged: true` for
  Docker-in-Docker (used by the separate Python code executor; the optimizer
  itself uses in-process subprocesses).

### Deployment configuration

**docker-compose** —
[deployment/docker-compose/docker-compose.yaml:227-275](../../../deployment/docker-compose/docker-compose.yaml#L227-L275).
The `python-backend` service env relevant to the Studio:

| Env var | Purpose |
|---------|---------|
| `OPIK_URL_OVERRIDE` (default `http://backend:8080`) | Base for `OPIK_GATEWAY_BASE_URL` (`+ /v1/private`) — see [studio/config.py:13](../src/opik_backend/studio/config.py#L13) |
| `REDIS_URL`, `RQ_WORKER_ENABLED` | RQ queue / worker toggle |
| `OPTSTUDIO_MAX_CONCURRENT_JOBS` (5) | Parallelism |
| `OPTSTUDIO_LOG_LEVEL`, `OPTSTUDIO_LLM_MAX_TOKENS`, `RQ_WORKER_TTL_FAILURE` | Tuning |

Notably, **no provider API key env vars are present** on this service.

**Helm** —
[deployment/helm_chart/opik/values.yaml:297-300](../../../deployment/helm_chart/opik/values.yaml#L297-L300)
already documents the new model explicitly:

> *"LLM provider API keys are no longer injected via environment variables.
> Configure them per workspace in the Opik UI under 'AI Providers' — the backend
> resolves the key from workspace settings when the Optimization Studio and
> Playground make LLM calls."*

### Studio config knobs

[studio/config.py](../src/opik_backend/studio/config.py) centralizes runtime
tuning read from env: `OPIK_GATEWAY_BASE_URL`, `OPTIMIZATION_TIMEOUT_SECS`
(default 24h), `DATASET_SAMPLES` (OOM guard), and `OPTIMIZER_RUNTIME_PARAMS`
(`max_trials`, GEPA / hierarchical params).

---

## 4. Key files map

| Concern | File |
|---------|------|
| Job entry / env setup | [jobs/optimizer.py](../src/opik_backend/jobs/optimizer.py) |
| Subprocess runner + gateway routing | [jobs/optimizer_runner.py](../src/opik_backend/jobs/optimizer_runner.py) |
| Subprocess isolation | [executor_isolated.py](../src/opik_backend/executor_isolated.py) |
| Studio config | [studio/config.py](../src/opik_backend/studio/config.py) |
| Job context / types / metrics / optimizers | [studio/](../src/opik_backend/studio/) |
| Log streaming to Redis | [subprocess_logger.py](../src/opik_backend/subprocess_logger.py) |
| RQ worker | [rq_worker.py](../src/opik_backend/rq_worker.py) |
| Gateway (Java) | `apps/opik-backend/.../v1/priv/ChatCompletionsResource.java` |
| Workspace provider keys (Java) | `apps/opik-backend/.../v1/priv/LlmProviderApiKeyResource.java` |
| docker-compose | [deployment/docker-compose/docker-compose.yaml](../../../deployment/docker-compose/docker-compose.yaml) |
| Helm values | [deployment/helm_chart/opik/values.yaml](../../../deployment/helm_chart/opik/values.yaml) |
