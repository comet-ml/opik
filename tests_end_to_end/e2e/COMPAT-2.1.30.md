# E2E compat notes — Opik 2.1.30

Branch: `e2e-compat/2.1.30` (baseline from `origin/main` @ `ca22d81a`).

This is the compatibility test branch for the **self-hosted 2.1.30 verification release**
(chart `v4.18.3-0+opik2.1.30`; upgraded from the previous 2.1.13 self-hosted release). It is the
successor to `e2e-compat/2.1.13`.

## What runs — the `@t1-stsaas` tier (includes Optimization Studio)

Run the compat suite with:

```bash
cd tests_end_to_end/e2e
npm run test:t1-stsaas   # --grep "@t1-smoke|@t1-stsaas"
```

The `@t1-stsaas` tier (added in [#7477](https://github.com/comet-ml/opik/pull/7477)) is the
`@t1-smoke` baseline **plus** the `@t1-stsaas`-tagged Optimization Studio coverage, so the studio
is exercised on the stsaas-shaped run without pulling in the rest of `@t2-cuj`.

### Optimization Studio coverage (this is the addition for this release)

`tests/optimization-studio/optimization-studio.spec.ts` (+ `pom/optimization-studio.page.ts`,
`core/backend/poll-optimization-status.ts`) — landed on `main` via
[#7477](https://github.com/comet-ml/opik/pull/7477) ([OPIK-7086]) and is included here as-is. It
drives the **v2** Optimization Studio end-to-end and is tagged
`['@t2-cuj', '@t1-stsaas', '@optimization-studio']`:

- **Optimization Studio — core**
  - `the new-run form renders its sections and enables Optimize only once valid` — the new-run
    sidebar (name / algorithm / prompt / item source / metric + metric settings) and the
    submit-enable gating (the OPIK-7042 sidebar).
  - `launches a GEPA + Equals run from the studio UI and it completes end-to-end` — full launch →
    runs-list → single-run overview → trials, GEPA optimizer + Equals metric.
- **Optimization Studio — variant**
  - `launches a Hierarchical Reflective + Equals run and it completes end-to-end` — the
    Hierarchical Reflective algorithm path.

> **Manual pre-check (2026-07-16, `self-hosted-eks`, Opik 2.1.30):** the studio was walked live —
> runs list, new-run sidebar, single-run overview (KPI deltas, progress chart, best-trial prompt),
> and the Trials tab all render and function; a completed GEPA/G-Eval run showed a full 7-trial
> optimization. The new-run **Metric** dropdown offers Equals / JSON Schema Validator /
> Custom (G-Eval) / Levenshtein / Numerical Similarity — there is **no CODE (custom-Python)
> metric** in the studio new-run flow in 2.1.30 (OPIK-7172 still In Review), so no compat test
> asserts a CODE option.

## Divergence from the tag

`tests_end_to_end/e2e/` is baselined from `main` @ `ca22d81a` (`version.txt` = `2.1.32`; `main`
runs ahead of the release tag, as usual). No product testids, flows, or selectors changed on the
`@t1-smoke`/`@t1-stsaas` surface between the `2.1.30` tag and this baseline, so there was **no
version-boundary tweaking** — nothing was restored from the tag or skipped for version skew.

The provider-fallthrough stabilization that `e2e-compat/2.1.13` had to carry
(shared `ensureModelAvailable`, provider-offered probe in `configuration.page.ts`) is **already on
`main`** (merged via [#7324](https://github.com/comet-ml/opik/pull/7324) /
[#7401](https://github.com/comet-ml/opik/pull/7401)), so this branch inherits it with no
cherry-picks.

## Skipped tests (no silent coverage gaps)

- **Ollie** (`ollie-smoke`, `ollie-agentic`) — skip on a local/OSS instance: Ollie is a cloud-only
  micro-frontend, absent there. Not a compat concern. On a full self-hosted target with Ollie
  deployed they run.

## Deployment-team prerequisites for the run

- `OPENROUTER_API_KEY` on the `comet-automation-tests` runner so the model-availability fallthrough
  can reach the Custom Provider path on restricted environments (same requirement as 2.1.13).
- A reachable 2.1.30 target and, for the studio launch tests, a working optimizer (GEPA /
  Hierarchical Reflective) + a scoring model the deployment offers.

## Result

Pending — to be filled in by the deployment team after running `npm run test:t1-stsaas` against a
2.1.30 target.

| Run | Config | Result |
|---|---|---|
| 1 | Full `test:t1-stsaas`, happy path | _pending_ |
| studio | `--grep @optimization-studio` (core + variant) | _pending_ |
