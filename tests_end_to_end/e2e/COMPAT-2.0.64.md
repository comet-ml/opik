# E2E compatibility branch — Opik 2.0.64

This branch (`e2e-compat/2.0.64`) is the `main` t1 smoke suite, validated to pass
against an Opik **2.0.64** deployment. The deployment team points the
`comet-automation-tests` regression workflow at this branch instead of `main` when
running against a customer on 2.0.64.

Only **t1-smoke** is run on customer envs, so only t1 was validated here.

## Result against 2.0.64

`npm run test:t1` → **14 passed, 1 skipped, 0 failed** (validated twice for stability:
1.0m and 49.7s runs, identical tally).

## Changes from `main`

**None.** No POM, spec, or fixture changes were needed — `main`'s t1 suite passes
against 2.0.64 as-is.

Why: at the time this branch was cut, `main` (`58c5d7c373`) was only **4 commits
ahead** of the 2.0.64 release build (`2.0.64-5661`, i.e. `git describe` =
`2.0.64-5661-4-g58c5d7c373`), and **none of those 4 commits touched the frontend UI
or `tests_end_to_end/`**. They were: an SDK annotation-queue feature, the version
bump (`version.txt` → 2.0.65 base), a backend CVE fix (dropping perl), and a
build-time bytecode optimization. So the UI/API the t1 suite exercises, and the
suite itself, are effectively the 2.0.64 release.

The branch exists so the deployment team has a stable, named ref to pin for 2.0.64
customers (matching the `e2e-compat/<version>` convention) and so this validation is
recorded — not because the suite diverged.

## Skipped on 2.0.64

- **`tests/ollie/ollie-smoke.spec.ts`** — `Ollie surface mounts and reaches a ready
  state` is skipped with `'Ollie is cloud/client-only (OLLIE_ENABLED off)'`. This is
  an **environment-driven** skip (Ollie is disabled by default on OSS/self-hosted
  deployments), **not** a version-compatibility skip. It skips identically on `main`
  and on any customer env where Ollie is off; it is not a coverage gap introduced by
  this branch.

> Note for contrast with `e2e-compat/2.0.61`: the two **chat-prompt** prompt-library
> tests that had to be skipped on 2.0.61 (missing `prompt-chat-messages` /
> `playground-message-editor` testids) **pass** on 2.0.64 — those features/testids
> exist in this version.

## Reproducing / refreshing

1. Stand up a local 2.0.64: `export OPIK_VERSION=2.0.64` then `./opik.sh`
   (verify `docker ps` shows the `:2.0.64` images, not `:latest`).
2. From `tests_end_to_end/e2e`: `npm ci && npx playwright install chromium`.
3. Put the LLM-judge key in a gitignored `.env.local` (`ANTHROPIC_API_KEY=…`).
   Playwright's `webServer` auto-starts the SDK bridge on port 5175 and inherits
   `process.env`, so the key reaches both Playwright and the bridge.
4. Run:
   ```bash
   set -a && . ./.env.local && set +a
   OPIK_DEPLOYMENT=oss OPIK_BASE_URL=http://localhost:5173 OPIK_WORKSPACE=default \
     WORKERS=2 npm run test:t1
   ```
