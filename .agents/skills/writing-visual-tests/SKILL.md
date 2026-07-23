---
name: writing-visual-tests
description: Use when a developer wants to add a visual regression (screenshot) test for an Opik UI page or panel — e.g. "add a visual test for the trace sidebar", "screenshot each tab of the dataset panel", "visual regression test for the new empty state". Covers the page-object pattern, seeding via the test-helper-service, unique screenshot naming, per-test masking, baseline (re)generation, and the local-run-until-stable loop in tests_end_to_end/visual-tests/.
---

# Writing Visual Tests

This skill adds a screenshot-comparison test to Opik's visual regression suite. Unlike the functional E2E suite (`tests_end_to_end/e2e/`, see `writing-e2e-tests`), this suite's assertion *is* the screenshot — so seeding must produce deterministic pixels, not just correct data.

**Announce at start:** "I'm using the writing-visual-tests skill to add a visual test for X."

## Where tests live

The suite is at `tests_end_to_end/visual-tests/`:

- **Specs:** `tests/<area>.spec.ts` — e.g. `visual-comparison.spec.ts` (happy-path pages), `empty-states.spec.ts`, `trace-sidebar.spec.ts`. One `test.describe` per spec, one `test.beforeAll` that seeds data and resolves the project ID.
- **Page objects:** `page-objects/<page>.page.ts` — see "Page object pattern" below; **always follow it**, don't freehand a different shape.
- **Seeding:** `helpers/test-helper-client.ts` — a typed HTTP client over the Flask `test-helper-service` (`../test-helper-service/routes/*.py`), which wraps the real Python SDK. Add a new route + a new client method together; never seed by clicking through the UI.
- **Screenshot util:** `tests/utils/screenshot.ts` — `screenshot(page, name, extraMasks?)`. Read this whole file before writing a new spec.
- **Global setup/teardown:** `global-setup.ts` / `global-teardown.ts` — create/delete the fixed-name projects (`visual-project`, `visual-empty-project`, `visual-sidebar-project`, …) used across specs. Fixed names, no timestamp suffix, so screenshots are identical across runs. **Give each new portion of screenshots its own project** — see "One project per screenshot group" below.
- **Baselines:** `screenshots/baseline/*.png` — see "Baselines are local and gitignored" below before you assume one exists.

## Page object pattern

Every existing page object (`projects.page.ts`, `experiments.page.ts`, `datasets.page.ts`, `test-suites.page.ts`, `logs.page.ts`, …) follows the same shape. New ones must match it exactly — don't introduce `test.step()` wrapping (that's the `e2e/` suite's convention, not this one), don't return locators from `goto()`, don't add a constructor signature that differs from the others.

```ts
import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class WidgetsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/widgets`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  // Races the populated state against the empty state so the same method
  // works whether the seeded project has data or not — never a bare
  // unconditional wait, which hangs forever if the match text is wrong.
  async waitForReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('heading', { name: 'Widgets', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await Promise.race([
      this.page.locator('tbody tr td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 }),
      this.page.getByRole('heading', { name: /no widgets yet/i }).waitFor({ state: 'visible', timeout: 8000 }),
    ]);
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: 'Widgets', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.getByRole('heading', { name: /no widgets yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
```

Rules that fall out of this:

- **Extend `BasePage`** and take the exact `(page, baseUrl, workspace)` constructor, even if a given page doesn't need `workspace` yet — consistency lets every spec construct page objects identically.
- **`goto()`** always: navigate via `this.url(...)` (never a raw string), `waitForLoadState('load')`, then `dismissWelcomeDialogIfPresent()`.
- **`waitForReady(expectedCellText)`** takes the text to match, doesn't hardcode it — callers pass whatever they actually seeded. See "Waiting: for content, not just structure" below for what's safe to pass.
- **`waitForEmpty()`** is a separate method, reused by `empty-states.spec.ts` — don't fold empty-state handling into `waitForReady` as a flag.
- Add new interaction methods (click, open, switch-tab) alongside `goto`/`waitForReady`, not in the spec file.

### Exception: panels and non-route components

A side panel (e.g. the trace/span detail panel) isn't its own route — it opens over an existing page. Don't force it into `BasePage`. Give it just a `Page` and a `root` locator scoped to the panel's own testid, then scope every other locator/action under `root`:

```ts
import { Page, Locator } from '@playwright/test';

export class TraceDetailsPanelPage {
  constructor(private page: Page) {}

  get root(): Locator {
    return this.page.getByTestId('traces');
  }

  async waitForLoaded(): Promise<void> {
    await this.root.getByRole('button', { name: 'Close' }).waitFor({ state: 'visible', timeout: 20000 });
    // ...
  }
}
```

Construct it from the *page* the panel opens over, in the spec: `new TraceDetailsPanelPage(page)`, right after the page object that opened it.

## Safety: verify local config before seeding

The Python SDK behind `test-helper-service` reads `~/.opik.config`. If it points at a cloud environment, seeding creates real data there.

```bash
cat ~/.opik.config
```

If `url_override` is anything other than `http://localhost:5173/api/`, back it up and point it local:

```bash
cp ~/.opik.config ~/.opik.config.bak-visual-test
cat > ~/.opik.config << 'EOF'
[opik]
url_override = http://localhost:5173/api/
workspace = default
EOF
```

When the work is done, restore it and stop `test-helper-service` — see the "Cleanup checklist" below before doing either: both are machine-wide, and blindly restoring/killing can break a concurrent run.

## Local environment

Use `./opik.sh` (repo root) for the Docker stack — **check what's already running before touching it**:

```bash
docker ps --format '{{.Names}}\t{{.Status}}'   # look for opik-opik-frontend-1, opik-opik-backend-1, etc.
./opik.sh --verify                             # only if you're unsure containers are healthy
```

If containers are already up (healthy, running), leave them alone — don't `--stop`/`--clean`/restart something a teammate or another task left running. Only run `./opik.sh` (no flags) to start what's missing.

Start the test helper service (check port 5555 is free first):

```bash
lsof -ti:5555                                  # empty output = free, safe to start
cd tests_end_to_end/test-helper-service
OPIK_BASE_URL=http://localhost:5173 OPIK_WORKSPACE=default TEST_HELPER_PORT=5555 \
  nohup .venv/bin/python app.py > /tmp/test-helper-service.log 2>&1 &
disown
curl -s http://localhost:5555/health
```

Restart it (`kill $(lsof -ti:5555)`, relaunch) whenever you change a route file — Flask's dev server doesn't hot-reload here. Same caution as everywhere else this port comes up: confirm the PID is actually yours before killing it (see "Cleanup checklist" below).

## Cleanup checklist

Everything this suite generates is gitignored (`tests_end_to_end/visual-tests/.gitignore` entries: `test-results/`, `visual-report/`, `.auth/`, `.test-state.json`, `screenshots/`; repo-wide: `allure-results/`) — nothing here risks a bad commit, but it does accumulate on disk across sessions. Work through this list before ending a visual-testing session, not just the config restore.

**`~/.opik.config` and the `test-helper-service` process are machine-wide, not scoped to your run.** If another agent, terminal, or teammate could be running visual/E2E tests concurrently on the same machine, check before you touch either — restoring the config or killing the process out from under a run in progress breaks it:

```bash
lsof -ti:5555                      # a PID here isn't necessarily "your" leftover — it may be someone else's active run
ps -p <pid> -o lstart,command      # when did it start, relative to when you started your own work?
```

If in doubt, ask rather than kill. This isn't hypothetical — it happened during this skill's own authoring session: a `test-helper-service` instance was found still listening after "cleanup," and it turned out to belong to a different run in progress, not a leftover.

Also note: `playwright.config.ts`'s `webServer` directive auto-spawns `test-helper-service` on port 5555 if nothing answers `/health` when a test starts (`reuseExistingServer: !process.env.CI`) — so an instance can appear that you never manually started, including right after your own "final run." Don't assume every process on that port traces back to a command you ran.

| Artifact | What it is | Action |
|---|---|---|
| `~/.opik.config` | Points the SDK at an environment | Confirm no concurrent run needs it local (see above), then **restore from your backup** (`cp ~/.opik.config.bak-visual-test ~/.opik.config`) and delete the backup file. |
| `test-helper-service` process (port 5555) | Flask bridge — may be one you started, or one `webServer` auto-spawned, or someone else's | Confirm it's actually idle/yours (see above) before `kill $(lsof -ti:5555)`. |
| Docker containers (`opik-opik-*`) | The stack under test | **Leave alone** if they were already running when you started (the common case) — don't `--stop`/`--clean` a stack you didn't start. Only stop containers you personally started for this session. |
| `test-results/` | Playwright's per-test output (failure screenshots, videos, traces) | Regenerated every run — safe to delete: `rm -rf test-results`. |
| `screenshots/comparison/` | Side-by-side copy written only when `SKIP_TEARDOWN=1` | Regenerated every run — safe to delete: `rm -rf screenshots/comparison`. |
| `visual-report/` | The HTML report (`npm run test:report`) | Safe to delete: `rm -rf visual-report`. |
| `allure-results/` | Allure reporter output | Safe to delete: `rm -rf allure-results`. |
| `.auth/`, `.test-state.json` | Storage state / experiment-ID bridge for non-`SKIP_TEARDOWN` runs | Removed automatically by `global-teardown.ts` on a normal run; delete by hand only if you aborted mid-run without ever finishing a non-`SKIP_TEARDOWN` pass. |
| `screenshots/baseline/*.png` | Your actual baselines | **Not cruft — don't reflexively delete.** Gitignored and per-machine by design (see "Baselines are local and gitignored"); leave them for the next person/session to reuse, unless you specifically want to force a clean re-baseline. |
| Seeded project (`visual-project` / `visual-empty-project`) in the running Opik instance | Server-side data, not a local file | Deleted by `global-teardown.ts` on a plain `npx playwright test` run (not `SKIP_TEARDOWN=1`). Do a final run *without* `SKIP_TEARDOWN` before you finish (see step 7) so this actually happens — don't leave it to the next person. |

One-shot cleanup for the local-only report/log artifacts (keeps baselines):

```bash
cd tests_end_to_end/visual-tests
rm -rf test-results screenshots/comparison visual-report allure-results
```

## Baselines are local and gitignored

`screenshots/` is gitignored (see `.gitignore`) — baseline PNGs are **never committed**. There is no CI job for this suite; it's a local, on-demand check. Two consequences:

- On a fresh clone, or after `git clean`, **no baselines exist at all** — every screenshot name in every spec needs generating before you can run a compare pass.
- Adding a *new* screenshot name always needs a fresh baseline, regardless of whether other baselines in the same directory already exist from a previous session.

**Always (re)generate baselines for whatever you touched** before judging a compare run — via `--update-snapshots` (see the loop below). Don't assume "the baseline is already there"; check, or just regenerate, it's cheap:

```bash
ls tests_end_to_end/visual-tests/screenshots/baseline/ | grep <your-prefix>
```

Regenerate whenever any of these change, not just on first add: the screenshot name, the masks passed to it, a wait added/removed before it, or the seeded data shape.

## The loop

1. **Scope.** Which page/panel, which states (tabs, empty vs. populated, with/without optional data). Check whether an existing spec's `beforeAll` already seeds a project/entity you can extend, or whether this needs its own.
2. **Seed data.** Add a `test-helper-service` route if no existing one produces the shape you need (see "Adding a seed endpoint" below), plus a `TestHelperClient` method.
3. **Page object(s).** Follow the pattern above exactly; reuse an existing page object if the page already has one.
4. **Spec.** One `test()` per screenshot, unique names (see "Unique screenshot names").
5. **Generate/refresh the baseline** — every time, whether or not one already exists (see "Baselines are local and gitignored" above):
   ```bash
   cd tests_end_to_end/visual-tests
   SKIP_TEARDOWN=1 OPIK_BASE_URL=http://localhost:5173 npx playwright test tests/<name>.spec.ts --update-snapshots --reporter=list
   ```
   `SKIP_TEARDOWN=1` keeps the seeded project alive for a fast next run; the plain `npx playwright test` invocation still always cleans and recreates it at the *start* (global-setup), so each run starts from a known state.
6. **Stress-test for stability — do not skip this.** Run the compare pass (no `--update-snapshots`) several times in a row:
   ```bash
   for i in 1 2 3 4 5; do
     echo "=== Run $i ==="
     SKIP_TEARDOWN=1 OPIK_BASE_URL=http://localhost:5173 npx playwright test tests/<name>.spec.ts --reporter=list
   done
   ```
   A single green run proves nothing — real timestamps, real durations, and font/layout jitter only show up over several runs. If anything fails, open the `*-diff.png` attachment under `test-results/` before touching anything — it tells you exactly which pixels moved. Don't guess. If you change the spec, masks, or seed data in response, go back to step 5 and regenerate the baseline before stress-testing again — a stale baseline will just fail for a different, misleading reason.
   > macOS gotcha: `timeout` is not a built-in command (no coreutils by default) — a loop like `timeout 60 npx playwright test ...` silently no-ops. Don't wrap runs in `timeout`.
7. **Final full run, then work through the "Cleanup checklist" above** — one run *without* `SKIP_TEARDOWN` first (so `global-teardown.ts` actually deletes the seeded project server-side), then every item in the checklist. Config restore and stopping `test-helper-service` are only two of several things left behind.

## Unique screenshot names

All specs share **one flat directory**, `screenshots/baseline/` (see `snapshotPathTemplate` in `playwright.config.ts`) — there is no per-spec-file subfolder. A name collision with another spec silently compares your new test against someone else's baseline (or overwrites theirs).

- Prefix every screenshot name with a short, spec-unique code, e.g. `visual-comparison.spec.ts` uses `01-`, `02-…`; `empty-states.spec.ts` uses `E01-`, `E02-…`; `trace-sidebar.spec.ts` uses `S01-`, `S02-…`. Pick a prefix letter/word not already in use.
- Before naming, check for collisions:
  ```bash
  grep -rn "screenshot(page, '" tests_end_to_end/visual-tests/tests/*.spec.ts
  ```
- Keep the rest of the name descriptive (`S02-trace-sidebar-details`, not `S02`) — the file is the only durable record of what a screenshot covers once `screenshots/` is gitignored.

## Masks: scope them to your test, don't touch the shared list

`tests/utils/screenshot.ts` has two tiers, not one shared list:

- `baseMasks(page)` (internal, always applied by `screenshot()`) — only page chrome that renders on virtually every screenshot and can legitimately vary between the two environments being compared: the breadcrumb (workspace/project name) and generic timestamp elements.
- `tableMasks(page)` (exported) — masks for populated data tables: relative/absolute date cells, UUID columns, duration cells, pagination "Showing X-Y of Z". Only screenshots of an actual table with rows need this (see `visual-comparison.spec.ts`'s tests 01-06, which pass `tableMasks(page)` explicitly). Empty-state screenshots have no rows and the trace sidebar has no table at all, so neither passes it.

Do not add a mask to `baseMasks` for something specific to your new page; it silently changes masking for every other visual test in the suite. If your new screenshot is a populated table, pass the existing `tableMasks(page)` — don't reinvent the same regexes. If it's something narrower (a single stat row, one specific element), pass a test-specific mask through `screenshot()`'s third argument instead:

```ts
// tests/utils/screenshot.ts
export async function screenshot(page: Page, name: string, extraMasks: Locator[] = []) { ... }
```

```ts
// your spec
await screenshot(page, 'S02-trace-sidebar-details', [panel.statsRowMask]);
// or, for a table page:
await screenshot(page, '07-widgets-page', tableMasks(page));
```

Expose the locator as a getter on the relevant page object (see `TraceDetailsPanelPage.statsRowMask`) so the masking rationale lives next to the DOM it targets, not buried in the spec.

Only promote a mask into `baseMasks` or `tableMasks` if the pattern is genuinely generic and reusable across many pages (e.g. "any `td` matching a relative-date regex") — not a one-off element on the one page you're testing.

### Mask the stable-sized container, not the small dynamic element

If the dynamic content sits inline in a flex/wrap row next to other elements (a stats bar, a badge row), masking just the small element isn't enough — its rendered width still varies by a pixel run to run (real timestamp/duration text, font sub-pixel rendering), which shifts every sibling after it even though they're masked-adjacent, not masked themselves. Symptom: a screenshot diff of only a few hundred/thousand pixels, always right next to a masked box, that comes and goes across runs.

Fix: mask the smallest **ancestor with a stable box** (e.g. one that's `w-full` or otherwise sized independent of its children) so internal jitter never leaks past the mask edge:

```ts
// Masks the whole stats row (created-at, duration, score counts) as one block,
// because that row is `w-full` — its own box doesn't move even when the text inside it does.
get statsRowMask(): Locator {
  return this.page.getByTestId('data-viewer-created-at').locator('xpath=..');
}
```

## Adding a seed endpoint

Follow the existing pattern in `test-helper-service/routes/*.py`: a Flask blueprint route using `get_opik_client()`, `validate_required_fields()`, `success_response()`. Then add a matching method to `TestHelperClient` in `helpers/test-helper-client.ts`.

**Make the seeded data deterministic — this is the #1 source of visual-test flakiness:**

- **Don't rely on `@track`/`opik_context` decorator timing** if the trace/span's duration will be visible on screen. Real wall-clock execution time varies run to run, even if it always rounds to "0s" — the rendered text can still differ by a sub-pixel width and shift siblings. Instead call `client.trace(...)` / `trace.span(...)` directly with an **identical `start_time` and `end_time`**, so duration is exactly zero every time:
  ```python
  now = datetime.datetime.now(datetime.timezone.utc)
  trace = client.trace(..., start_time=now, end_time=now)
  span = trace.span(..., start_time=now, end_time=now)
  ```
- **Log at most one feedback score per trace/span in a screenshot-visible table** unless you've confirmed the UI sorts them deterministically. Two scores logged in the same call have no guaranteed render order (no explicit sort key), so a table showing both can silently swap row order between runs.
- **To attach a prompt to a trace/span for the Prompts tab**, don't rely on the `@track` context helpers — build the metadata directly so it works with plain `client.trace()`:
  ```python
  prompt = client.create_prompt(name=..., prompt=...)
  client.trace(..., metadata={"opik_prompts": [prompt.__internal_api__to_info_dict__()]})
  ```
- Attachments: resolve paths via the existing `resolve_attachment_path()` helper (relative to `tests_end_to_end/`), and check in a small fixture file under `visual-tests/fixtures/` if one doesn't already exist for your case.

## Waiting: for content, not just structure

`waitForReady()` methods should wait for the *specific value* you seeded to be visible (e.g. a known input string), not just a generic loading state. Many Opik tables don't render a "Name" column by default, so `waitFor({ hasText: entityName })` can time out even though the row is there — match on whatever text is actually visible in the default column set (e.g. the input/output preview), not the entity's name.

For any panel with lazy-loaded content (a trace/span side panel's Input/Output, media, etc.), wait for its loading placeholder to clear before switching tabs, and again after switching tabs, before screenshotting:

```ts
await this.root.getByText('Loading', { exact: true }).waitFor({ state: 'hidden', timeout: 15000 });
```

## One project per screenshot group

Don't seed a new spec's data into an existing spec's shared project (e.g. `visual-project`) just because it's already there and already has a `beforeAll` you could piggyback on. Every spec file that seeds its own traces/threads/datasets should create and use its **own dedicated project** in `global-setup.ts`/`global-teardown.ts` (see `visual-sidebar-project` for `trace-sidebar.spec.ts`).

Reusing a shared project causes two distinct problems, both discovered the hard way while adding `trace-sidebar.spec.ts`:

- **Cross-spec pollution.** Data seeded by spec B's `beforeAll` becomes visible in spec A's table screenshot (an extra row, a shifted count/chart) even though spec A never changed — because both specs' entities live in the same project. This produces a confusing, flaky-looking diff in a test you didn't touch.
- **Non-idempotent-seed collisions compound.** Combined with the hook-restart gotcha below, two specs sharing one project multiplies the chance that a retried `beforeAll` collides with another spec's still-present data (dataset/name conflicts, unexpected table rows) — independent of any bug in your own spec.

Cost is low — `global-setup.ts` already loops over project creation — so default to a new project per spec unless it's an empty-state test explicitly reusing `visual-empty-project` on purpose.

## Known Playwright gotcha: hook re-run on failure

If a test in a `describe` block fails, Playwright restarts the worker process before the next test in that file — which re-runs `test.beforeAll`. If your `beforeAll` seeds data (as ours does), one real failure produces a **second copy** of the seeded entity before the next test runs, which can cascade into unrelated-looking failures in every subsequent test in the file (growing diffs, extra table rows). When diagnosing a multi-test failure, always look at the **first** failure in isolation — it's usually the only real bug; the rest may just be contamination from the worker restart.

## Anti-patterns

| Symptom | What you skipped |
|---|---|
| "The baseline folder already has PNGs, I don't need `--update-snapshots`" | Baselines are gitignored and per-machine — a name you just added has no baseline yet, regardless of what else is in the folder. |
| "It passed once, ship it" | The stress-test loop — real timestamps and font jitter are intermittent by nature; one green run doesn't prove stability. |
| "I'll match the row by trace/dataset name" | Checking what's actually rendered — many tables don't show a Name column; match visible cell text instead. |
| "I'll add this mask to `baseMasks()`, it's easier" | Scoping — that changes every other visual test's masking. Use `tableMasks(page)` if it's a table page, or `screenshot()`'s `extraMasks` param otherwise. |
| "I'll mask just the timestamp `div`" | Checking whether it sits in a flex row with siblings — mask the stable-sized parent instead, or the siblings will still jitter. |
| "The decorator's duration always shows 0s, it's fine" | Determinism — "usually 0s" still varies by a sub-pixel width; force it to exactly 0 with matching `start_time`/`end_time`. |
| "All 4 tests failed, must be a bigger bug" | The hook-restart gotcha — check the *first* failure alone before assuming later ones are independent. |
| "I'll just seed my new spec into `visual-project`, it's already there" | One project per screenshot group — reusing another spec's project pollutes its table screenshots with your seeded data. |
| "I'll wrap this panel in `BasePage`" | The panel exception — a non-route panel takes just `Page` + a `root` testid locator, not the full `BasePage` constructor. |
| "I stopped the test-helper-service, I'm done" | The rest of the cleanup checklist — config restore, a non-`SKIP_TEARDOWN` final run (server-side project deletion), and the local report/log directories (`test-results/`, `visual-report/`, `allure-results/`, `screenshots/comparison/`). |
