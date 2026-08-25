# E2E Test Conventions

These are the durable conventions for the Opik E2E suite (`tests_end_to_end/e2e/`). Read this before writing any Page Object Model or spec. They aren't style preferences — each one prevents a class of failure or makes failures legible.

## `test.step()` wrapping is mandatory

Wrap logical phases at the test level, and wrap each POM method body in a `test.step()` that returns through the callback. This is what makes the Playwright trace viewer and the Allure timeline readable — without it, a failure is a flat wall of actions with no narrative.

Granularity: a "phase" is something you'd describe in a complete sentence ("seed three traces", "open the trace and verify the panel").

In the test:

```ts
import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

test('Logs view shows seeded traces in order', async ({ project, sdkClient, page }) => {
  await test.step('Seed traces via the Python SDK', async () => {
    await sdkClient.python.createTrace({ project_name: project.name, name: 'a', input: 'i', output: 'o' });
  });

  await test.step('Open Logs and verify', async () => {
    const logs = new LogsPage(page);
    await logs.goto(project.id);
    await logs.waitForReady();
    expect(await logs.countTraces()).toBe(1);
  });
});
```

In a POM method — wrap the body and return through the step callback:

```ts
async openDatasetByName(name: string): Promise<DatasetItemsPage> {
  return test.step(`open dataset "${name}"`, async () => {
    const row = this.datasetRow(name);
    await row.waitFor({ state: 'visible' });
    await row.getByRole('cell', { name, exact: true }).click();
    return new DatasetItemsPage(this.page, this.projectId, datasetId);
  });
}
```

## UI-first assertions by default

Assert on what the user sees, using Playwright's built-in locator assertions:

```ts
await expect(panel.traceNameInHeader(trace.name)).toBeVisible();
await expect(logs.row(name)).toHaveCount(1);
await expect(panel.errorBadge).toBeHidden();
```

Don't register custom matchers. Touch `matchers/register.ts` only if you've identified a specific assertion the built-in locator assertions genuinely can't express — and if you do, the test you ship must actually use it. A registered-but-unused matcher is dead code.

When you need to confirm something the UI doesn't surface (e.g. a feedback score landed on a source trace after an async rule), read it back through the suite's SDK client — but prefer a UI assertion whenever the UI shows the fact.

## Selector preference

Pick the most stable locator available, in this order:

1. **`getByTestId('descriptive-name')`** — the FE team's stability contract. Use it whenever it exists.
2. **`getByRole('button', { name: 'Create dataset' })`** — survives most refactors as long as the accessible name holds.
3. **`getByLabel('Dataset name')`** — for labelled form inputs.
4. **`getByText(...)`** — only for truly static, non-i18n text.
5. **CSS / XPath** — last resort.

If the only working selector is a structural CSS path (`tbody > tr:nth-child(2)`), stop: add a descriptive kebab-case `data-testid` to the FE component (under `apps/opik-frontend/src/v2/pages/<Page>/...` or its shared dependency) in the **same change** as the POM. Name it for the page/element (`create-dataset-sidebar`, `dataset-items-table`), never generically (`button-1`, `submit`). If you genuinely can't touch the FE, leave a comment explaining why the CSS selector is necessary and that a `data-testid` should be added.

## Public SDK surface only

Seed and inspect through the suite's SDK clients and the public `Opik` class. Never deep-import REST internals (`opik/rest_api/*`). The public surface is the contract; internals move.

## Seed state via the SDK/bridge, not the UI

Create the state a page needs through the bridge or SDK before you open the browser. UI-create is what the *test* exercises — it's not how you set up for exploration or for a precondition. UI-create is also slower and flakier as a setup step.

## Fixture seed shapes must match what the page renders

An empty project shows only the empty state; a dataset with no items shows only the "create item" CTA. If your page needs rows, sort order, or a pass/fail mix to be meaningful, the fixture must seed that shape. Reuse existing fixtures (`project`, `dataset`, `trace`, `experiment`, `testSuite`) where they fit; add a new one only when the shape genuinely differs. Verify teardown: some entities cascade with the project, some need explicit deletion — check and clean up what you create.

**Adding a fixture is normal.** Extending `fixtures/` (or `core/backend/client.ts`) is
the expected way to seed and tear down, not a scope overrun to apologise for. Reuse first;
when nothing fits, add one.

## Teardown belongs in a fixture, not in the test body

If a test creates an entity, a fixture owns its deletion. Playwright runs fixture teardown
whether the test passes, fails, or times out, so a fixture gets you the guarantee a
`try`/`finally` is reaching for — without putting cleanup mechanics in the middle of the
assertions, where it also escapes `test.step()` and so never appears in the trace or the
Allure timeline.

Two shapes, depending on when the id exists:

- **Known upfront** — a plain fixture that seeds in setup and deletes after `use()`
  (`project`, `dataset`, `experiment`, `bystanderExperiment`, …).
- **Not known until mid-test** — a register-callback fixture: the test calls it the moment
  the id exists, and the fixture drains its registry at teardown. See
  `registerPromptCleanup` and `registerDatasetCleanup`. Reach for this when a test creates
  something a seed fixture cannot predict, e.g. an entity recreated after a delete.

A destructive-action test wants a **bystander** — a second entity it never touches — so
that "the target disappeared" cannot also be satisfied by a delete that took the whole
project. `bystanderExperiment`, `bystanderTestSuite` and `bystanderPrompt` exist for this.

Beware ids that are not the id you think: the Python SDK's `create_prompt` returns the
prompt **version** id, and `DELETE /v1/private/prompts/{id}` answers 404 for it — so
teardown registered with that id silently leaks. Resolve via
`backendClient.findPromptIdByName(name, projectId)`.

## Verify the test render before blaming the backend

When something "isn't appearing," the usual cause is a DOM race (a loading spinner still up, an eventually-consistent write not yet landed), not a backend regression. Read the failure trace artifact first — `npx playwright show-trace` — and confirm the page actually finished rendering before concluding the data is missing. For genuinely async state (online scoring rules, ingestion lag), poll with `expect.poll(...)` rather than a fixed `setTimeout`.

## Tags

Every spec carries a tier tag and an `@area:` tag on the describe block, and at least one
`@cap:` tag naming the capability it asserts. Tiers are inclusive: `test:t2` runs
`@t1-smoke|@t2-cuj`, `test:t3` runs all three.

- `@t1-smoke` — fast, deterministic, always-on core checks.
- `@t2-cuj` — core user journeys; multi-step flows.
- `@t3-nightly` — broader / slower coverage.

```ts
test.describe('Trace Explore — smoke', { tag: ['@t1-smoke', '@area:traces'] }, () => {
  test('Logs view shows seeded traces', { tag: ['@cap:traces.list-traces'] }, async ({ page }) => {
    // ...
  });
});
```

### `@area:` and `@cap:` values MUST exist in the taxonomy

`tests_end_to_end/coverage/taxonomy.yaml` is the source of truth for both, and CI enforces it via
the **`tag-lint`** job. **Never invent a tag name.** An unregistered value fails the build with
`unknown capability '@cap:x.y' — not in taxonomy`.

The taxonomy usually already reserves the capability you're about to cover, as
`covered: false`. Grep before you write:

```bash
grep -n "your-feature" tests_end_to_end/coverage/taxonomy.yaml
```

If an entry exists, **use that exact name** and flip it to `covered: true` with the tier you
assigned. If none fits, add a new one under the right area — a kebab-case name describing the
user-facing capability, not the test's mechanics.

A `@cap:` is written `@cap:<area>.<capability>` and must sit under the spec's own `@area:`.

### Keeping the taxonomy in sync

Adding or changing a spec means editing `taxonomy.yaml` in the **same** change:

1. Add the spec's path to the area's `specs:` list.
2. For each capability the spec now covers: set `covered: true` and record the `tier:`.
3. If you're covering something not yet listed, add the capability entry first.

### Run the linter before you push

It's fast, needs no browser, and catches exactly the class of mistake CI would:

```bash
python3 tests_end_to_end/coverage/tag_lint.py --taxonomy tests_end_to_end/coverage/taxonomy.yaml --estate tests_end_to_end
```

Expect `0 problem(s)`. Full grammar: `tests_end_to_end/TESTING-TAGS.md`.
