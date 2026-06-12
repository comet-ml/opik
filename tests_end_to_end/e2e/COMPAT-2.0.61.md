# E2E compatibility branch — Opik 2.0.61

This branch (`e2e-compat/2.0.61`) is the `main` t1 smoke suite, tweaked so every
test passes against an Opik **2.0.61** deployment. The deployment team points the
`comet-automation-tests` regression workflow at this branch instead of `main` when
running against a customer on 2.0.61.

Only **t1-smoke** is run on customer envs, so only t1 was validated here.

## Result against 2.0.61

`npm run test:t1` → **12 passed, 2 skipped, 0 failed** (validated twice for stability).

## Changes from `main`

### Adapted to run on 2.0.61
- **`pom/datasets.page.ts`** — restored the pre-OPIK-6818 3-step create flow
  (`Next` → "Add data" → `Create` → "Dataset created!" → `Close`). Main's
  SDK-card sidebar create flow does not exist in 2.0.61.
- **`pom/test-suites.page.ts`** — restored the pre-OPIK-6818 3-step create flow
  (inline assertions/advanced settings + "Test suite created!" success screen).
  Removed the `pendingCriteria` / post-create Test settings application that the
  redesigned flow needs.
- **`tests/test-suites/test-suites-smoke.spec.ts`** — the 3-step UI create commits
  an initial version, so a UI-created suite is born at v1; the first SDK item
  insert bumps it to v2. Expect the version label to reach `v2` (matches 2.0.61
  behaviour; `main` expects `v1` because its empty SDK-create flow has no initial
  version).
- **`pom/prompt-detail.page.ts`** — `activeVersionLabel()` and
  `versionHistoryItem()` no longer use the `active-version-label` /
  `version-history-item-*` testids (added after 2.0.61). They target the header
  version badge and the version-history list items by text/structure instead.

### Skipped on 2.0.61 (NOT covered here)
- **`tests/prompts/prompt-library-smoke.spec.ts`** — the two **chat-prompt** tests
  (`SDK-seeded chat prompt …`, `UI-created chat prompt …`) are `test.skip`-ped.
  They depend on the `prompt-chat-messages` and `playground-message-editor`
  testids, which did not exist in 2.0.61. The two **text-prompt** tests run.

## Reproducing / refreshing

1. Stand up a local 2.0.61: `export OPIK_VERSION=2.0.61` then `./opik.sh`
   (verify `docker ps` shows the `:2.0.61` images, not `:latest`).
2. Start the SDK bridge with the judge key:
   `OPIK_URL_OVERRIDE=http://localhost:5173/api ANTHROPIC_API_KEY=… uv run uvicorn opik_sdk_driver.main:app --port 5175`
3. Run: `OPIK_DEPLOYMENT=oss OPIK_BASE_URL=http://localhost:5173 OPIK_SDK_DRIVER_URL=http://localhost:5175 ANTHROPIC_API_KEY=… npm run test:t1`
