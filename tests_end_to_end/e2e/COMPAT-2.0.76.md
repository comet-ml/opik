# E2E compat — Opik 2.0.76

Branch: `e2e-compat/2.0.76` (baselined from `origin/main`).
Target version: **2.0.76** — there is no clean `2.0.76` git tag; the release build
tag is `2.0.76-5767` (cut 2026-06-23), which is the ref used for all version
comparisons and POM restores. Running images report `:2.0.76`.

For the deployment team: point the `comet-automation-tests` regression workflow at
`e2e-compat/2.0.76` instead of `main` when running against a 2.0.76 customer env.

## Result

| Run | Passed | Skipped | Failed |
|-----|--------|---------|--------|
| Baseline (`test:t1`) | 16 | 2 | 0 |
| Confirmation (`test:t1`, re-run) | 16 | 2 | 0 |

18 t1-smoke tests total. Validated twice (LLM-judge tests carry stochasticity);
identical, stable, all green.

## Adapted files

**None.** No test tweaks, POM restores, or skips were needed for 2.0.76.

The e2e suite on `main` is identical to the `2.0.76-5767` tag for test purposes:

```
git log --oneline 2.0.76-5767..origin/main -- tests_end_to_end/e2e   # (empty)
```

`main` was only ~9 commits / ~6h ahead of the tag at branch time, and none of
those commits touched `tests_end_to_end/e2e` or shipped frontend/UI changes that
t1 selectors depend on (they were SDK/docs/backend-internal: thread query
optimizations, dataset TZ fix, OpenTelemetry monitoring, ClickHouse error
handling, uuidValidation default). So `main`'s t1 suite already matches 2.0.76 —
no version skew to fix.

## Skipped tests

The 2 skips are **environment-driven, not version-boundary** — they skip on `main`
against any local OSS instance (including latest), so they are not 2.0.76-specific
and were not introduced by this branch:

| Test | Reason |
|------|--------|
| `tests/ollie/ollie-smoke.spec.ts` › Ollie surface mounts and reaches a ready state | `test.skip(!envConfig.features.ollie, 'Ollie is cloud/client-only (OLLIE_ENABLED off)')` — Ollie is not enabled on a local OSS deployment |
| `tests/ollie/ollie-agentic.spec.ts` › Ollie sidebar mounts on a project page and persists across navigation | same `OLLIE_ENABLED off` guard |

The `test.skip(true, 'Neither ANTHROPIC_API_KEY nor OPENAI_API_KEY is set')` guards
on the LLM-judge specs (online-evaluation, test-suites, playground, prompts) did
**not** trigger — `ANTHROPIC_API_KEY` was set for both the Playwright suite and the
SDK bridge (port 5175), so those judge tests ran and passed.

## How it was run

```bash
# instance: ./opik.sh with OPIK_VERSION=2.0.76-5767 (images verified :2.0.76)
# SDK bridge (port 5175) — key needed here too or judges 401
cd services/opik-sdk-driver && uv sync
OPIK_URL_OVERRIDE=http://localhost:5173/api OPIK_WORKSPACE=default \
  ANTHROPIC_API_KEY=… uv run uvicorn opik_sdk_driver.main:app --port 5175

# suite
set -a && . ./.env.local && set +a
OPIK_DEPLOYMENT=oss OPIK_BASE_URL=http://localhost:5173 OPIK_WORKSPACE=default \
  OPIK_SDK_DRIVER_URL=http://localhost:5175 WORKERS=2 npm run test:t1
```
