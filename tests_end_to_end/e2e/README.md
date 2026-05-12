# Opik 2.0 E2E Test Suite

Infrastructure foundation for the Opik 2.0 E2E suite. CUJ tests land in
follow-up tickets — see `docs/superpowers/specs/2026-04-23-opik-2.0-e2e-infrastructure-design.md`.

## What's here

- `config/env.config.ts` — deployment-aware config (oss / cloud / self-hosted)
- `core/backend/` — typed REST client for project list/create/delete (inspection + teardown only)
- `global-setup.ts` — stamps a per-run `runId`, propagates it to workers, sweeps `cuj-*` projects older than 6h
- `global-teardown.ts` — sweeps every `cuj-{runId}-*` project created during this run

## Quick start

```bash
npm ci
npx playwright install chromium
OPIK_DEPLOYMENT=cloud \
  OPIK_BASE_URL=https://staging.dev.comet.com/opik/api \
  OPIK_API_KEY=<your key> \
  OPIK_WORKSPACE=<your workspace> \
  npm run test
```

(No tests exist yet — the suite will boot, print the env banner, sweep orphans, and exit clean.)

## Environment variables

- `OPIK_DEPLOYMENT` — `cloud` | `oss` | `self-hosted` (default: `oss`)
- `OPIK_BASE_URL` — required for cloud/self-hosted; defaults to `http://localhost:5173` for oss. Trailing `/api` is normalized away.
- `OPIK_API_KEY` — Opik API key (required for cloud)
- `OPIK_WORKSPACE` — workspace slug (defaults to `OPIK_TEST_USER_NAME` on cloud, `default` on oss)
- `OPIK_TEST_USER_EMAIL`, `OPIK_TEST_USER_PASSWORD`, `OPIK_TEST_USER_NAME` — required for cloud (browser-login flow lands in Phase 2)
- `OLLIE_ENABLED` — `true`/`false`, defaults per deployment
- `OPIK_CONNECT_ENABLED` — `true`/`false`, defaults per deployment
- `ANTHROPIC_API_KEY` — enables Layer B LLM judges; if unset, judges skip
- `SKIP_LLM_JUDGES` — `true` to force-skip Layer B calls
- `OPIK_LEAVE_FAILURES` — `true` to preserve failed-test entities for inspection
- `WORKERS` — worker count override
- `OPIK_RUN_ID` — internal; set by `globalSetup` so workers share the parent's runId

## Naming convention

Every test-created entity is namespaced `cuj-{runId}-w{worker}-{slug}` where `runId = YYYYMMDD-HHMMSS-mmm` UTC. Teardown sweeps the full `cuj-{runId}-*` prefix.
