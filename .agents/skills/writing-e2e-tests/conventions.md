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

## Verify the test render before blaming the backend

When something "isn't appearing," the usual cause is a DOM race (a loading spinner still up, an eventually-consistent write not yet landed), not a backend regression. Read the failure trace artifact first — `npx playwright show-trace` — and confirm the page actually finished rendering before concluding the data is missing. For genuinely async state (online scoring rules, ingestion lag), poll with `expect.poll(...)` rather than a fixed `setTimeout`.

## Tags

Every test carries a tier tag and a feature tag. Tiers are inclusive: `test:t2` runs `@t1-smoke|@t2-cuj`, `test:t3` runs all three.

- `@t1-smoke` — fast, deterministic, always-on core checks.
- `@t2-cuj` — core user journeys; multi-step flows.
- `@t3-nightly` — broader / slower coverage.

Apply them on the describe block alongside the feature tag:

```ts
test.describe('Dataset CRUD — smoke', { tag: ['@t1-smoke', '@datasets'] }, () => {
  // ...
});
```

Pick the tier by what the test costs and how core it is; pick the feature tag to match the page family (`@datasets`, `@trace-explore`, `@experiments`, …).

**Exception — release-gate specs.** Dev-authored release-gate tests are the one case that does *not* follow the rules above: they live at `tests/_release-gate/<lead-ticket>.spec.ts` (not `tests/<feature>/`) and carry `@release-gate` + `@release-gate:<version>` instead of a tier tag, so they stay out of the curated `@t1-smoke`/`@t2-cuj`/`@t3-nightly` suites. They are governed by `.agents/skills/explore-feature/release-gate-contract.md`, not this file. If you're following these conventions, treat a spec under `_release-gate/` as intentional, not a violation.
