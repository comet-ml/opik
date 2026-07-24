# Release-gate authoring contract

When delegating to the `writing-e2e-tests` skill, hand it this contract verbatim. It exists
because `writing-e2e-tests` defaults to local-OSS output that ends at "green locally" — a gate
test must be **staging-ready** and stamped. This contract overrides those defaults.

## Output location & naming

- Write to `tests_end_to_end/e2e/tests/_release-gate/<lead-ticket>.spec.ts` (lowercased ticket
  key, e.g. `opik-7167.spec.ts`).
- If that file already exists → **append mode**: add a new `test()` (or extend the flow) inside
  the existing `test.describe`. Do not create a second file.

## Tags (on the `test.describe`)

```ts
{ tag: ['@release-gate', '@release-gate:<version>', '@<feature>'] }
```

- `@release-gate` — always.
- `@release-gate:<version>` — the stamp. `<version>` = `git show origin/main:version.txt` read at
  authoring time (see the stamp rules in SKILL.md). Never `@t1-smoke`/`@t2-cuj`/`@t3-nightly` —
  gate tests are not part of the curated tiers.
- `@<feature>` — the page-family tag matching the change (`@datasets`, `@trace-explore`, …).

**Append reconciliation:** if appending, the describe block keeps the **earliest un-shipped**
`@release-gate:<v>` across its tests, so the assembled feature gates the earliest-targeted release.

## Must be deployment-agnostic

- baseURL / auth / workspace come from `OPIK_DEPLOYMENT` (the suite's existing env config) — never
  hardcode `http://localhost:5173`.
- Self-seed all required state via the SDK/bridge (fixtures or `sdkClient`), and tear down what it
  creates. The same spec must run unchanged on staging.

## POM policy

- If a POM already covers the page (`pom/<name>.page.ts`), use/extend it.
- If not, prefer a small **inline** spec over authoring a full throwaway POM. QA POM-ifies on
  promotion. Keep the per-PR cost cheap.

## Seeding is in scope

The happy path's precondition state must be created before the browser opens. If an existing
fixture or SDK client already produces the needed shape, use it. If not — the shape the gate needs
isn't reachable through the current bridge/fixtures — **add that seeding support as part of this
work**: extend the bridge route (`services/opik-sdk-driver`), add a fixture, or drive the public
SDK client directly. A missing seed path is authoring work to do, not a reason to skip the gate.
Only stop if the state can't be produced through any public SDK / bridgeable path at all.

When you add a new bridge route, **prove the seed shape end-to-end before writing the browser
test** — call the route with `curl` and read the entities back through the private REST API to
confirm they match the exact shape the feature queries. Two traps produce a silently wrong seed
(and a red test that looks like a product bug):

- **Entity association / scoping.** Entity-scoped views (experiment/playground/trial logs) usually
  query by the *parent entity's* `project_id`, not the project you passed when creating child rows.
  If you create traces in project X but `create_experiment()` without `project_name` (it defaults
  to "Default Project"), the experiment lands in a different project and the scoped view queries
  the wrong one → empty list. Make every linked entity share one project, and verify the parent's
  stored `project_id` equals the children's.
- **Eventual consistency of joins.** A join like experiment→trace (`experiment_items`) is
  eventually consistent and lags plain trace visibility. Waiting for the trace to be queryable is
  not enough; have the bridge poll the *same* query the UI runs (e.g. `experiment_id = "…"` via
  `search_traces`) until all rows return, so the browser never opens ahead of the data.

Seed via a **realistic** path (the one a user/SDK takes to reach the repro shape) and let the
**UI** be the thing under test. Confirm the repro at the API layer (fixed query returns rows,
pre-fix query returns none) before trusting the browser assertion.

## Conventions (inherited from the suite)

Follow `tests_end_to_end/e2e/` conventions: `test.step()` wrapping, UI-first assertions, selector
preference (testid → role → label → text → CSS), public-SDK-only seeding. The spec header is
minimal — ticket key + one-line scope only; no restated plan (it lives in Jira).

## Verification

Explore the live UI and run the spec **green against the dev's local stack** (see the local-run
gate in SKILL.md). Local-green is the PR gate; staging-green (CI, later) is the release gate.
