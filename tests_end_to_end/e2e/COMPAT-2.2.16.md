# E2E compatibility — Opik 2.2.16

`e2e-compat/2.2.16` — branched from `origin/main` at `6b59553bd9`.

**No test changes were needed.** Main's t1 suite passes against 2.2.16 as-is. This
branch exists so the deployment team has a pinned, verified ref for 2.2.16 runs;
it is `main` with this document added.

## Scope

Validated `npm run test:t1-stsaas` (`@t1-smoke` + `@t1-stsaas`) — the target the
deployment team runs. Wider than the usual t1-only compat scope because the
`@t1-stsaas` optimization-studio tests were the specific concern for this version.

## Result

Two full runs against a local 2.2.16 instance, `WORKERS=2`:

| Run | Passed | Failed | Skipped |
|---|---|---|---|
| 1 | 20 | 1 (`playground-smoke`, flake — see below) | 2 |
| 2 | **21** | **0** | 2 |

23 tests total. Optimization studio (all 3, including both end-to-end optimizer
runs) passed in **both** runs.

## Why 2.2.16 needs no adaptation

`2.2.16` is a clean ancestor of `main`, 8 commits back. The only commit touching
`tests_end_to_end/e2e` in that range is `6b59553bd9` (studio prompt-editor role
scoping), and it is compatible with 2.2.16:

- `playground-message-row` and `data-role` — the selectors the role-scoped POM
  depends on — both exist at 2.2.16
  (`v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessage.tsx`).
- `LLMPromptMessages/` and `OptimizationConfigForm/` are byte-identical between
  the tag and main.
- 2.2.16's own FE unit test already asserts *"seeds a system and a user message
  for a new run"* — the two-card behaviour the spec's `systemPrompt` field
  exists to satisfy.

So `6b59553bd9` fixed a test bug, not a product change. FE churn since the tag is
confined to optimization chart/page rendering, which t1 does not assert on.

## Skipped tests

Both skips are pre-existing suite behaviour on local OSS, not compat gaps:

- `ollie/ollie-smoke.spec.ts:11` — *Ollie surface mounts and reaches a ready state*
- `ollie/ollie-agentic.spec.ts:25` — *Ollie sidebar mounts on a project page and persists across navigation*

Both self-skip with `Ollie is cloud/client-only (OLLIE_ENABLED off)`. On a
deployment with Ollie enabled they execute normally. **Ollie is therefore
unverified for 2.2.16 by this run.**

## Known flake (not version-related)

`playground/playground-smoke.spec.ts:13` — *Run prompts against a dataset
auto-creates an experiment* — failed in run 1, passed in run 2 and passed in
isolation (10.5s).

Failure mode: the playground run itself completed (3 output rows rendered), but
the latched `POST /v1/private/experiments` never arrived within 120s; the trace's
network log contains no experiment request at all. Ruled out as version skew —
the spec is identical at `2.2.16` and `main`, and the latch commit (`bda4b16690`)
predates the tag. Consistent with contention on the shared playground/LLM path
under `WORKERS=2`. Left as-is; no quarantine.

## Environment notes for re-runs

- Instance must be pinned — `opik.sh` ignores `version.txt` and defaults to
  `:latest`:
  ```bash
  export OPIK_VERSION=2.2.16
  TOGGLE_FORCE_WORKSPACE_VERSION="version_2" ./opik.sh
  ```
  Verify: `docker ps` shows `:2.2.16` on backend/frontend/python-backend, and
  `curl -s http://localhost:5173/api/is-alive/ver` reports `2.2.16`.
- The SDK bridge (`e2e/services/opik-sdk-driver`, port 5175) is a separate
  process and needs `ANTHROPIC_API_KEY` in **its own** environment, not just
  Playwright's. Without it, LLM-judge tests report `items_passed: 0` while
  appearing to run.
- Bridge `opik` pin was refreshed 2.2.13 → **2.2.17** for this run.
- The studio logs-download check self-skips its content assertion on local OSS:
  MinIO signs the URL with its internal `http://minio:9000` host, unreachable
  from the runner. Environment artifact, not a product issue.
