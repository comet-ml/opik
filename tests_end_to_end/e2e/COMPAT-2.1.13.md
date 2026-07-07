# E2E compat notes — Opik 2.1.13

Branch: `e2e-compat/2.1.13` (baseline from `origin/main`).

## Result

| Run | Config | Result |
|---|---|---|
| 1 | Full `test:t1`, happy path (Anthropic offered by deployment) | 18 passed / 2 skipped |
| 2 | Full `test:t1`, happy path (stability re-run) | 18 passed / 2 skipped |
| forced | 3 affected specs, built-in keys unset → OpenRouter Custom Provider forced | 4 passed / 1 skipped |

The 2 skips in every full run are the **Ollie** tests (`ollie-smoke`, `ollie-agentic`) — Ollie
is a cloud-only micro-frontend, absent on a local OSS instance. Not a compat concern.

## Divergence from the tag

`tests_end_to_end/e2e/` diverges from the `2.1.13` tag by a single commit (#7321, an
SDK-bridge fix that improves test-suite smoke reliability). No product testids, flows, or
selectors changed between the tag and `main` for the t1 surface, so there was **no
version-boundary tweaking** — nothing was restored from the tag or skipped for version skew.

## Adapted files (not version skew — a latent logic bug present on both `main` and `2.1.13`)

The deployment team's regression run against a restricted customer environment (only Custom
Provider / Bedrock / local Llama offered, built-ins gated off by feature toggles) failed 3 t1
tests — all timing out on `[data-provider="anthropic"]` in the add-provider dialog. Root cause:
the `ensureModelAvailable` helpers picked a provider by which API key the **runner** had, then
committed to it, so a runner with `ANTHROPIC_API_KEY` always tried Anthropic even on a
deployment that doesn't offer it. The dialog omits ungated providers entirely (they are not
rendered, confirmed in `ProviderGrid.tsx` + `useProviderOptions.ts`), so the click hung 15s.

- **`pom/configuration.page.ts`** — `ensureProviderConfigured` now returns `boolean`: it probes
  whether the provider's option is actually offered in the dialog (short 2s wait) and returns
  `false` (dismissing the dialog) instead of hanging when it is absent, so the caller can fall
  through to the next candidate.
- **`pom/model-availability.ts`** (new) — single shared `ensureModelAvailable` that tries
  Anthropic → OpenAI → OpenRouter (Custom Provider), attempting each built-in only when its key
  is present **and** the deployment offers it, and falling through otherwise. Replaces five
  duplicated per-spec copies.
- **`tests/playground/playground-smoke.spec.ts`**,
  **`tests/test-suites/test-suites-smoke.spec.ts`**,
  **`tests/online-evaluation/online-evaluation-smoke.spec.ts`**,
  **`tests/prompts/prompt-version-playground.spec.ts`**,
  **`tests/prompts/prompt-playground-traces.spec.ts`** — dropped the local `ensureModelAvailable`
  copy and import the shared helper.

Validation of the fallthrough: with the built-in keys unset (simulating the restricted env),
the 3 affected tests were forced through the OpenRouter Custom Provider path and passed; the
backend confirmed a `custom-llm` / `openrouter` provider row was provisioned and the model
returned real completions.

## Skipped tests (no silent coverage gaps)

- **Ollie** (`ollie-smoke`, `ollie-agentic`) — skip locally: cloud-only surface, absent on OSS.
- **`test-suites-smoke.spec.ts:14` (Test A, "SDK-seeded suite … 3/3 pass experiment")** — this
  test's LLM judge runs inside the **SDK bridge** via LiteLLM and is keyed on
  `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` only (its own skip guard, unchanged by this fix). In the
  forced-OpenRouter validation run it skipped because the built-in keys were unset. In a normal
  run with a built-in key present it runs and passes. It is **not** one of the Netflix-failing
  tests. If a fully restricted env must also run Test A, its bridge-side `judge_model` derivation
  needs an OpenRouter case and the bridge needs `OPENROUTER_API_KEY` — out of scope here.

## Deployment-team prerequisites for the restricted-env run

- `OPENROUTER_API_KEY` must be set on the `comet-automation-tests` runner (confirmed present) so
  the fallthrough reaches the Custom Provider path. Without it, the 3 tests skip cleanly (green,
  but not exercised) rather than fail.

## Fix also lands on `main`

This is a logic bug present identically on `main` and `2.1.13`, so the fix is authored against
`main` (PR) and every future regression run benefits — not just this compat branch.

## Added test — Optimization Studio smoke (`@t1-smoke`)

`tests/optimization-studio/optimization-studio-smoke.spec.ts` (+ `pom/optimization-studio.page.ts`)
was added on this branch to verify the Optimization Studio, which is deployed across the staaS
environments and had no automated check. It drives the **v2** studio end-to-end: it SDK-seeds a
4-item sentiment dataset (`text` / `label`), opens `/projects/{id}/optimizations/new`, keeps the
form's default GEPA optimizer + Equals metric, picks the env-configured model
(`ensureModelAvailable`, Anthropic Haiku preferred), sets the Equals reference key to `label`, and
clicks **Optimize prompt**. It then polls `GET /v1/private/optimizations/{id}` until the run reaches
`completed` (the python-backend RQ worker executes it out of band), asserts the run is healthy
(`objective_name=equals`, `num_trials ≥ 1`), and confirms the detail page shows the `completed`
status. Runs green in ~26–28s locally (validated 3×).

- **Selectors:** the v2 studio/detail pages carry no product `data-testid`s except the shared LLM
  message editor (`playground-message-editor`), and per compat-branch rules no testids were added
  (the deployed image wouldn't have them). Everything else is role/label/text — see the POM.
- **Seeding note:** the studio dataset picker lists only datasets **associated with the project**
  (`GET /projects/{id}/datasets`). A dataset must be created with `project_name` set, which the SDK
  bridge's `createDataset` does; a plain workspace-level dataset does not appear.
- **Skip guard:** with no LLM provider key (`ANTHROPIC_API_KEY` / `OPENAI_API_KEY` /
  `OPENROUTER_API_KEY`), `ensureModelAvailable` skips the test cleanly (validated) rather than
  failing — so restricted environments stay green.
- **Duration:** the run is naturally fast (4 items, Haiku, GEPA, deterministic Equals), so
  `OPTIMIZER_MAX_TRIALS` did not need bounding on the python-backend.

### Updated result tally with the new test

| Run | Config | Result |
|---|---|---|
| A | `optimization-studio-smoke` alone, ×3 (stability) | 3× 1 passed (~26–28s) |
| B | `optimization-studio-smoke`, provider keys unset | 1 skipped (clean skip guard) |
