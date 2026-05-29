# CUJ Test Retro Lessons (Opik 2.0 E2E)

Supplementary reading for the `playwright-pom-discovery` skill. Captures lessons from previously-shipped CUJ tests under `tests_end_to_end/e2e/tests/` (Opik 2.0 suite, not the 1.0 suite at `tests_end_to_end/typescript-tests/`). Read this once when invoking the skill on a new CUJ ticket.

## Lessons from OPIK-6595 (Dataset CRUD, PR #6851)

### 7. Bridge routes now use `opik_factory.make_opik_client(...)`, not `opik.Opik(...)` direct

OPIK-6595 introduced `services/opik-sdk-driver/src/opik_sdk_driver/opik_factory.py` with a `make_opik_client(workspace=..., api_key=...)` helper. New bridge routes follow this pattern.

### 8. Bridge routes can accept per-request auth via `X-Opik-Api-Key` header

OPIK-6595's `POST /datasets` route signature:

```python
def create_dataset(body: ..., x_opik_api_key: str | None = Header(default=None)) -> ...:
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
```

Tests can pass per-request auth this way. Match the pattern for new bridge routes.

### 9. Cascade behavior is per-entity — verify in Phase 2

OPIK-6595 discovered that **datasets do NOT cascade-delete with their parent project** — explicit teardown is required. Don't assume project deletion cleans up child entities. During Phase 2, after creating an entity via the bridge route, verify whether deleting the project cleans it up. If not, the fixture needs explicit teardown AND `global-teardown.ts` needs an entity-specific sweep added BEFORE the project sweep (datasets-before-projects is the established order).

### 10. The fixture composition chain is growing — pick the right parent

As of OPIK-6595, the chain is:

```
base → project → scratch-dir → failure-artifacts → trace → dataset → ...
```

Each new fixture extends the highest-level ancestor it actually needs (so transitively gets everything below). For OPIK-6596 (Experiments), the question is whether to extend `dataset` (if experiments always need a dataset) or `trace` (if some experiment flows don't need datasets). Pick based on dependency, not on "extend the latest."

### 11. The test discovered a UI feature that wasn't in the spec — that's fine

OPIK-6595's spec didn't predict that "commit changes" creates a new dataset version (v2, v3). The agent discovered this during Phase 3 discovery and the test now asserts on version labels. This is the kind of discovery the 5-phase model is designed to surface. Future agents: if Phase 3 reveals UI behavior the spec didn't anticipate, flag it; if it makes the test more meaningful, incorporate it; if it's tangential, defer.

### 12. Reusing an earlier fixture means inheriting its seed shape — check that fits the new ticket's assertions

OPIK-6595's `dataset.fixture` seeds 3 items, all happy-path (`input` / `expected_output` matched). That's perfect for "list page shows 3 rows" but useless for "experiment evaluator caught a failure on item 3."

When OPIK-6596 (Experiments) reuses `dataset.fixture`, it inherits happy-path-only seed data and can only assert on happy paths. **Red-path coverage matters** — an evaluator that silently scores everything as 1.0 should fail the test. The fix: OPIK-6596's `experiment.fixture` seeds its OWN dataset with a 2-pass-1-fail item shape, deliberately not reusing `dataset.fixture`.

**The general principle:** when a new ticket reuses a fixture, look at its seed data and ask "does this shape let me assert on both the success AND failure surfaces of my CUJ?" If the answer is "only success," seed your own shape — even if it means writing a similar-looking fixture. The maintenance cost of two fixtures is less than the regression cost of a test that can't see failure.

This applies broadly:
- Online evaluation (OPIK-6597): the `trace` fixture seeds one happy trace. To test "rule fires and scores match/no-match correctly," the test creates additional traces inline (one matching, one not) — don't try to make the `trace` fixture parameterized.
- Annotation queue (OPIK-6598): seed traces include at least one that the test will score, one that it'll skip, and one that it'll mark as failing.
- Any future ticket reusing existing fixtures: same question.

### 13. Wrap test phases in `test.step(...)` — Allure + trace-viewer rely on it

Every CUJ test from OPIK-6596 onwards MUST wrap its logical phases in `await test.step('description', async () => { ... })`. This is non-negotiable for two reasons:

1. **Allure integration is in flight** (parent design §14 Phase 4). When allure-playwright ships, each `test.step()` becomes a labeled row in the test timeline. A flat test renders as one opaque block; debugging requires watching the video. Tests written without steps now will need painful refactor when Allure lands.
2. **Playwright's own trace viewer** (`npx playwright show-trace`) renders steps as collapsible timeline blocks. Steps are a usability win locally even before Allure.

**What counts as a "phase":** a logical block you'd describe with a complete sentence. Examples (good granularity):

- `await test.step('seed three traces with sequential timestamps', async () => { ... })`
- `await test.step('navigate to Logs and verify trace count', async () => { ... })`
- `await test.step('open first trace and assert panel renders', async () => { ... })`
- `await test.step('UI-create dataset via dialog', async () => { ... })`
- `await test.step('verify dataset via SDK roundtrip', async () => { ... })`

**Bad granularity** (don't do these):

- Wrapping every line in its own step (`test.step('click button')`, `test.step('wait for visible')`) — overkill, makes the timeline noisy.
- Wrapping nothing (the current OPIK-6128 / OPIK-6595 shape) — see the rationale above.
- Wrapping fixture setup from within the fixture itself — fixtures already attach `testInfo` annotations; leaking fixture internals into the test report just adds noise.

**POM methods also get steps**, with a different granularity:

```ts
async openTraceById(traceId: string): Promise<TracePanelPage> {
  return test.step(`open trace ${traceId}`, async () => {
    // ... existing implementation
  });
}
```

POM steps are the FINE-grained "click row", "wait for panel ready" level; the test's steps are the COARSE-grained "open trace and verify panel" level. Nested steps in the Allure / trace viewer give you both granularities — the user-journey narrative AND the locator-level detail when you drill in. Each POM method does exactly one user-visible action AND wraps its body in `test.step()`.

**Returning from a stepped POM method:**
The `test.step()` callback must return whatever the POM method returns:

```ts
async openTraceById(traceId: string): Promise<TracePanelPage> {
  return test.step(`open trace ${traceId}`, async () => {
    await this.page.goto(/* ... */);
    return new TracePanelPage(this.page, traceId);
  });
}
```

**Existing tests (OPIK-6128, OPIK-6595):** retrofit is not required mid-wave. New tests onward (OPIK-6596+) MUST follow this. The two existing tests get a step-wrapping cleanup ticket filed later (low priority, can land alongside the Allure wiring).

## Lessons from OPIK-6128 (Trace Explore, merged 2026-05-25, PR #6823)

These are enforceable defaults. If you deviate, name the reason in the implementation.

### 1. Default to UI-first assertions with Playwright built-ins

Assert what the user sees in the rendered DOM, not what the REST API returns. Use Playwright's built-in locator assertions: `await expect(locator).toBeVisible()`, `await expect(locator).toHaveCount(n)`, `await expect(locator).toHaveText(...)`, `await expect(locator).toBeHidden()`. These cover essentially every CUJ assertion the data-plane suite needs.

**Do NOT add to `matchers/register.ts`** by default. The file exists from OPIK-6128 but its six exports are dead code — none are consumed by the merged test. Custom matcher registration is an opt-in decision that requires explicit justification AND actual consumption in the test you ship.

When you might genuinely need a custom matcher:

- Asserting against data that the UI doesn't render but the user's journey depends on (e.g., a hidden span-tree shape that's accessed via REST after a UI action).
- Asserting against a structural shape that would be very verbose to express with built-ins (e.g., "spans form a tree where every llm-typed node has a tool-typed parent").

When you don't:

- "I want a nicer error message" — write a clearer Playwright assertion with a custom locator name instead.
- "I want this matcher available for future tests" — YAGNI. Add it when the future test ships.
- "The OPIK-6128 matcher file already exists, I'll just extend it" — no. Extending dead code propagates the dead code.

**Anti-pattern from OPIK-6128:** six matchers (`toHaveSpanOfType`, `toHaveSpanCount`, `toHaveAtLeastSpanCount`, `toHaveValidInput`, `toHaveValidOutput`, `toHaveNoErrors`) were built during Phase 4 then orphaned when the test refactored to UI-first assertions. The file is in the repo, untouched, doing nothing. Don't repeat this.

### 2. Auth is owned by `global-setup.ts`, not by `.auth/` symlinks

The Opik 2.0 E2E suite logs in via HTTP POST to `<rootBase>/api/auth/login` during `global-setup`, captures the API key from the response, mints `.auth/user.json` storage state at runtime, and propagates the API key to workers via `process.env`.

This means:

- **Don't symlink** `.auth/staging.json` between worktrees — there's no such file. The file is `.auth/user.json` and global-setup creates it fresh each run.
- **Cloud auth requires `OPIK_TEST_USER_EMAIL` + `OPIK_TEST_USER_PASSWORD`** in env. See `.env.cloud.example` for the full env-var shape.
- **`OPIK_API_KEY` alone is not enough** for UI tests — global-setup needs to be able to log in to mint storage state. Power-user shortcut: set `OPIK_API_KEY` AND pre-populate `.auth/user.json` and global-setup will trust both and skip the login.
- **OSS deployments skip auth** — `global-setup` short-circuits when `deployment=oss`.

### 3. Inspection methods on `backendClient` are pre-blessed

Per parent design §7.4, `core/backend/client.ts` is for inspection + teardown only. Adding read methods (`getProject`, `getTrace`, `getTraceSpans`, `findDatasetByName`, etc.) is expected and welcome — not scope creep. If your test needs to inspect backend state during development or during a verification gate, add the wrapper method. Don't add WRITE methods (create/update) — those belong in `sdkClient` per the SDK-first principle.

### 4. FE `data-testid` additions in the same PR, default

When Phase 3 discovery finds an element you'd need a brittle selector for (CSS path, `:nth-child`, regex body-text parse), the default is to **add a `data-testid` to the FE component in the SAME PR**. The QA team has explicit permission for cross-package changes per parent design §10.4.

Fallback selectors (`getByRole`, `getByText`, deterministic class/role combinations) are only acceptable when there's a named reason: FE owner unreachable, the element is third-party (Radix/Shadcn), or the testid would be too generic to be useful.

**Don't ship body-text regex parsing** like `document.body.innerText.match(/Traces (\d+)/)` without a paired FE testid PR. That's a workaround, not a selector, and it accumulates fragility across tickets.

### 5. Spec case counts are tentative

If the spec proposes 3 test cases and you find two of them are redundant (one is a subset of the other), STOP, flag it to the user, and propose collapsing to fewer cases. The spec is a starting point. Don't ship 3 cases just because the spec said 3.

OPIK-6128 was specced with 3 cases and shipped with 2 after a refactor commit collapsed the redundant one.

### 6. Don't refactor adjacent code mid-ticket

If during a CUJ ticket you notice that `core/sdk/`, `core/backend/`, `fixtures/`, or the bridge needs improvement that's outside the ticket's scope — STOP and file it as a separate ticket. Do not bundle the refactor into the CUJ PR. The PR review focus dilutes; the diff gets harder to reason about.

**OPIK-6128 exception:** the global-setup auth flow rewrite was substantial and unplanned, but it's structurally part of the CUJ test (you can't run the test without auth, and the old approach was broken). That's the bar: the refactor must be load-bearing for the ticket's own functionality, not just adjacent improvement.

## Things that are NOT lessons (in case the agent guesses)

- **The `playwright-pom-discovery` skill is correct as-is.** Use it before any POM selector work. It enforced good discipline on OPIK-6128.
- **The 5-phase model is correct.** Plan → bridge+SDK+fixture → discovery → POM+test → PR. Don't compress phases unless a phase is genuinely empty for the ticket.
- **`comet:create-pr` skill works.** Use it for the PR. Don't hand-roll `gh pr create` unless the skill fails.
- **Worktree pattern `.worktrees/<branch>` is correct.** The parent repo's `.git/info/exclude` already ignores `.worktrees/`.
