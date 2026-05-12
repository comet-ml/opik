# Opik 2.0 E2E Test Suite

See the full design spec at `docs/superpowers/specs/2026-04-23-opik-2.0-e2e-infrastructure-design.md`.

## Quick start

```bash
npm ci
npx playwright install chromium
OPIK_DEPLOYMENT=oss OPIK_BASE_URL=http://localhost:5173 npm run test
```

## Environment variables

- `OPIK_DEPLOYMENT` — one of `cloud`, `oss`, `self-hosted`
- `OPIK_BASE_URL` — required for cloud/self-hosted, defaults to `http://localhost:5173` for oss
- `OPIK_TEST_USER_EMAIL`, `OPIK_TEST_USER_PASSWORD` — required for cloud
- `OLLIE_ENABLED` — `true`/`false`, defaults per deployment
- `OPIK_CONNECT_ENABLED` — `true`/`false`, defaults per deployment
- `ANTHROPIC_API_KEY` — enables Layer B LLM judges; if unset, judges skip
- `SKIP_LLM_JUDGES` — `true` to force-skip Layer B calls
- `OPIK_LEAVE_FAILURES` — `true` to preserve failed-test entities for inspection
- `WORKERS` — worker count override

## Scripts

- `npm run test` — all tests
- `npm run test:t1` — T1 smoke tier (deploy-blocking)
- `npm run test:t2` — T2 CUJ tier (2-hourly cron)
- `npm run test:t3` — T3 nightly tier (full regression)
- `npm run test:feature @agent-config` — filter by tag
