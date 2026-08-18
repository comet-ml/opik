# E2E compatibility — Opik 2.2.30

`e2e-compat/2.2.30` — branched from `origin/main` at `33b447a`.

**No version-compatibility changes were needed.** Main's t1 suite runs against
2.2.30 as-is. This branch exists so the deployment team has a pinned ref for
2.2.30 runs; it is `main` with this document added, and nothing else.

## Scope

Validated target: `npm run test:t1-stsaas` (`@t1-smoke` + `@t1-stsaas`) — what
the deployment team runs for STSaaS sanity.

**14 specs resolve for this target** at `33b447a`: the 13 carrying `@t1-smoke`
plus `optimization-studio/optimization-studio.spec.ts`, which is the **only**
spec carrying `@t1-stsaas`.

> ⚠️ **The 2.2.29 → 2.2.30 release-exploration specs are NOT in this target.**
> `5bdc57bfd` ([OPIK-7791](https://comet-ml.atlassian.net/browse/OPIK-7791)) added
> three specs aimed at this release, and they are on this branch — but all three are
> tagged **`@t2-cuj` only, with no `@t1-stsaas`**:
>
> | Spec | Tags | Actually targets |
> |---|---|---|
> | `optimization-studio/optimization-trial-logs.spec.ts` | `@t2-cuj`, `@area:optimization-studio` | OPIK-6739 / OPIK-7842 — trial Logs-overlay scope lock |
> | `trace-explore/thread-id-prefilter.spec.ts` | `@t2-cuj`, `@area:threads` | OPIK-7919 — `traces_final_ids` prefilter |
> | `.../experiment-logs-date-window.spec.ts` | `@t2-cuj` | OPIK-7842 — 30-day window hiding aged experiments |
>
> So `test:t1-stsaas` resolves to the same shape as it did for 2.2.16 — branching from
> `5bdc57bfd` does **not** widen it. To exercise the new specs run
> `npm run test:t2` (`@t1-smoke|@t2-cuj`) instead, or add the suite tag deliberately.
>
> Note also that the first two do **not** cover what their filenames suggest:
> neither touches the mini-batch/full-eval separation, the GEPA reflection spend, or the
> chart tick labels, and the `optimization-run` fixture **seeds** a completed run rather
> than launching one ("The run is seeded, not launched") — so it never exercises reaper
> liveness.

## Optimization Studio coverage included

`optimization-studio.spec.ts` — two describes, 3 tests: form validation
(`assertFormRenders`, Optimize-enable gating), a full **GEPA + Equals** run, and a
**Hierarchical Reflective** run. Both run tests assert the run is *healthy and
complete* and confirm `algorithm`/`metric` on the best-trial config. They
deliberately **never** assert that the score improved.

Not asserted by any spec in this target: mini-batch vs full-eval score separation
([OPIK-7460](https://comet-ml.atlassian.net/browse/OPIK-7460)), GEPA reflection spend
([OPIK-7521](https://comet-ml.atlassian.net/browse/OPIK-7521)), or chart tick labels
([OPIK-7589](https://comet-ml.atlassian.net/browse/OPIK-7589)). All three **are**
covered by frontend unit tests under `apps/opik-frontend/src/v2/` — they are an
e2e gap, not an untested-code gap.

## Divergence from the tag

None. This branch is `main` at `33b447a` plus this file.

`2.2.30` is an ancestor of `main`. Everything in `tests_end_to_end/e2e` on this
branch is at or ahead of the tag, and no spec required adaptation to run against a
2.2.30 instance. Unlike the 2.2.16 cycle, **no cherry-pick was needed** — the
OPIK-7806 model-picker hardening that had to be pulled onto `e2e-compat/2.2.16` is
already on `main` here.

## Skipped tests

Ollie specs self-skip with `Ollie is cloud/client-only (OLLIE_ENABLED off)` —
`test.skip(!envConfig.features.ollie, …)` in `ollie-smoke.spec.ts`,
`ollie-agentic.spec.ts`, `ollie-connect.spec.ts`, `ollie-explain.spec.ts`. On a
deployment with Ollie enabled they execute normally. **Ollie is therefore unverified
by a run where the toggle is off.**

## Prerequisites

- `OPENROUTER_API_KEY` must be present on the runner (the studio runs need a live
  LLM provider).
- Instance must be pinned — `opik.sh` ignores `version.txt` and defaults to `:latest`:
  ```bash
  export OPIK_VERSION=2.2.30
  TOGGLE_FORCE_WORKSPACE_VERSION="version_2" ./opik.sh
  ```
  Verify: `docker ps` shows `:2.2.30` on backend/frontend/python-backend, and
  `curl -s http://localhost:5173/api/is-alive/ver` reports `2.2.30`.
- The SDK bridge (`e2e/services/opik-sdk-driver`, port 5175) is a separate process and
  needs `ANTHROPIC_API_KEY` in **its own** environment, not just Playwright's. Without
  it, LLM-judge tests report `items_passed: 0` while appearing to run.

## Known environment-dependent failure — OPIK-7348

The studio logs-download assertion **hard-fails on a cloud / self-hosted deployment**
and only self-skips on local OSS. On `self-hosted-eks` running 2.2.30 it fails with:

```
logs presigned URL must be reachable on a cloud deployment — … the object-store
presign host (http://comet-ml-minio:9000/comet-ml-data/logs/optimization-studio/…)
is not client-reachable and the in-app logs viewer will be broken
```

This is [OPIK-7348](https://comet-ml.atlassian.net/browse/OPIK-7348), **still open on
2.2.30** — the presigned URL is signed against the cluster-internal MinIO host
(`IS_MINIO=false` while bundled `comet-ml-minio` is running). It is a real,
pre-existing env/config bug, **not** a compat problem with this branch, and it will
fail on every self-hosted run until fixed. Verified independently on
`self-hosted-eks` (Allure launch 97879, and by hand in the browser) on 2026-08-18.

## Results

Pending — not run against a live 2.2.30 instance from this branch.

| Run | Passed | Failed | Skipped |
|---|---|---|---|
| — | — | — | — |

For reference, the same env was exercised by the **t3** suite via
`comet-automation-tests` (Allure launch
[97879](https://comet.testops.cloud/launch/97879), CI run 32130935164) against Opik
2.2.30 on `self-hosted-eks`: **67 passed, 2 failed** — the OPIK-7348 logs assertion
above, and one test-side race in `online-evaluation.page.ts` where the cmdk popover
overlays the submit button (no product-side error; same family as the OPIK-7806
hardening, which did not cover that call site). Two further tests failed once and
passed on retry (a known-flaky trace-filter chip test and the OPIK-7806 model-picker
shape).
