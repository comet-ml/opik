# Universal CUJ Kickoff Prompt (Opik 2.0 E2E)

**How to use this:** open a fresh Claude Code session in the opik repo root. Either:

- **Have a Jira ticket?** Edit the `TICKET` line below to the ticket key (e.g., `TICKET: OPIK-6595`). Paste everything from the horizontal rule down.
- **No ticket — just a natural-language request?** You don't even need this prompt. Just say what you want in plain English ("write tests for the agent-config switcher flow" / "we need E2E coverage for experiments"). The `playwright-pom-discovery` skill auto-triggers on phrases like that, handles the scoping conversation, and asks if you want it to file a Jira ticket or ship as `[NA]`. See `tests_end_to_end/e2e/AGENTIC.md` for examples.

Either way the agent stops at the Phase 1 spec gate, the Phase 3 discovery gate, and (if anything goes sideways) at any escalation point.

**This prompt is the shared team contract for the data-plane CUJ test sequence.** It lives in `tests_end_to_end/e2e/.agentic/` and is updated when patterns shift (after each CUJ ticket lands). See `tests_end_to_end/e2e/AGENTIC.md` for the onboarding overview.

---

TICKET: <<<EDIT THIS — e.g. OPIK-6595, or leave blank/replace with a natural-language description of the flow you want tested>>>

You're picking up the work above. It's a CUJ test (or test family) for the Opik 2.0 E2E suite. This is your only piece of work for this session — finish it, ship the PR, then stop. Don't pick up another one.

**If the TICKET line above is a Jira key (`OPIK-XXXX`):** proceed via the ticket-anchored path — fetch the ticket via the Atlassian MCP, use the cheat-sheet table below for known gotchas, drive the 5-phase model.

**If the TICKET line is blank, says "no ticket", or contains a natural-language description:** invoke the `playwright-pom-discovery` skill immediately. It has a "Path B" entry point for natural-language requests — handles scoping in 1-5 adaptive questions, asks about Jira vs `[NA]`, drafts a one-pager spec, then joins the standard 5-phase model from Phase 2 onwards.

# What you're walking into

The Opik 2.0 E2E test suite lives at `tests_end_to_end/e2e/` (separate from the 1.0 suite at `tests_end_to_end/typescript-tests/`). The infrastructure is built and merged on `main`:

- **OPIK-6126** — env config, global setup/teardown, orphan project sweep.
- **OPIK-6498** — FastAPI bridge at `localhost:5175` (Python SDK wrapper) with `health` + `projects` routes.
- **OPIK-6499** — TypeScript SDK clients: `sdkClient.python` (HTTP wrapper over bridge), `sdkClient.typescript` (direct `new Opik({...})`), `backendClient` (cutover to public `Opik().api.*` for inspection/teardown).
- **OPIK-6500** — base fixtures: `base`, `project`, `scratchDir`, `failureArtifacts`; `playwright.config.ts` `webServer` directive auto-spawns the bridge during tests.
- **OPIK-6128** — first CUJ test (Trace Explore). PR #6823 is the reference shape for everything you'll build. Read it.

The current session's first task is to read three things:

1. **The Jira ticket description** — via the Atlassian MCP. The ticket carries the scope contract.

2. **The closest-shape merged PR for your ticket** — fetch via `gh pr view <number> --json files,commits` and read the test file, POMs, bridge route, fixture. Recent examples on `main`:
   - **OPIK-6128 (PR #6823)** — Trace Explore. SDK-create + UI-verify. Reference for fixture extending `failure-artifacts`, POM patterns, UI-first assertions.
   - **OPIK-6595 (PR #6851)** — Dataset CRUD. SDK-create + UI-create pair pattern. Reference for `opik_factory.make_opik_client`, `X-Opik-Api-Key` header, explicit teardown for non-cascading entities, FE testid additions in same PR.
   - Newer CUJs as they land. Find the merged precedents under `tests_end_to_end/e2e/tests/<feature>/` on `main`.

3. **The CUJ retro lessons** at `.agents/skills/playwright-pom-discovery/retro-lessons.md` — supplementary to the `playwright-pom-discovery` skill. Read it once at the start; it captures hard-won lessons from prior CUJ tickets (matchers default off, UI-first assertions, FE testid policy, `test.step()` mandatory, cascade verification, fixture-seed-shape considerations, etc.).

# The per-ticket cheat sheet

Find your ticket below. The cheat-sheet line is opinion, not gospel — the spec you write in Phase 1 is where you commit to design choices.

| Ticket | Entity / fixture | Bridge route(s) | POMs | Key gotchas |
|---|---|---|---|---|
| **OPIK-6595** | `dataset` fixture, depends on `project` | `POST /datasets` (create with N items); add `GET /datasets/by-name` only if Phase 4 tests need it | `DatasetsPage` (list + create dialog), `DatasetItemsPage` (item table + add/edit/delete) | Dataset and TestSuite share the `Dataset` DB table with a `DatasetType` discriminator (`DATASET` vs `TEST_SUITE`). Different user-level entity, different fixture, different POMs. Don't reuse the future `testSuite` fixture. First ticket exercising the SDK-create + UI-create pair-in-one-PR pattern. |
| **OPIK-6137** | `testSuite` fixture, depends on `project` | `POST /test-suites` (create with items + deterministic evaluator) | `TestSuitesPage` (list + create dialog), `TestSuitePage` (suite detail with run trigger), `TestSuiteItemsPage` (per-item results) | Separate entity from Dataset at the user level. Python SDK class is `opik.TestSuite`, NOT `opik.Dataset`. Backend `DatasetType.TEST_SUITE` enum value is the string `evaluation_suite` (pending OPIK-5795 rename). Trigger-run-via-UI is part of the journey per parent §11.2 — don't trigger run via SDK. |
| **OPIK-6596** | `experiment` fixture, depends on `project` directly (NOT on `dataset.fixture`) — seeds its OWN dataset with a 2-pass + 1-fail shape, see notes below | `POST /experiments/evaluate` (with deterministic scorer) | `ExperimentsPage` (list), `ExperimentDetailPage` (per-item metric values, status) | T1 = deterministic evaluator ONLY (`Equals`, `Contains`, etc.). No LLM judges — those reintroduce non-determinism the smoke can't tolerate. **Do NOT extend `dataset.fixture` from OPIK-6595** — its 3 items are all happy-path. Experiments need red-path coverage. Instead, the `experiment` fixture seeds its own dataset shaped: <br>• `{ input: "X", expected_output: "Y" }` where the task returns `"Y"` → score 1.0 <br>• `{ input: "A", expected_output: "B" }` where the task returns `"B"` → score 1.0 <br>• `{ input: "Q", expected_output: "Z" }` where the task returns `"WRONG"` → score 0.0 <br>The evaluator is `Equals` (or similar deterministic exact-match). Test assertions verify: experiment row appears in list with status COMPLETED; detail page shows 3 items; 2 marked passed, 1 marked failed; aggregate score = 2/3. Bridge route uses `opik_factory.make_opik_client(...)` per OPIK-6595's pattern, accepts `x_opik_api_key` header. UI-create-experiment flow is multi-step (select dataset + evaluator + target); defer to T2 follow-up, only ship SDK-create + UI-verify here. **Verify cascade behavior**: do experiments delete with their parent dataset? Their parent project? Check during Phase 2; if not cascading, add explicit teardown to fixture AND an experiments sweep to `global-teardown.ts` BEFORE the dataset sweep (dependency order: experiments → datasets → projects). |
| **OPIK-6597** | reuses `trace` fixture from OPIK-6128 | (reuses `POST /traces`) | `OnlineEvaluationPage` (rule list + create dialog) | First test in the suite that asserts on eventually-consistent state. Rules apply asynchronously — assertion shape is "wait up to 30s for feedback score to land." Build a polling helper (e.g., `LogsPage.waitForFeedbackScore(traceId, ruleName)`), not a `setTimeout` race. Use Playwright's built-in `expect.poll(...)` if it fits. Confirmed by user: online evals enabled by default in every environment. |
| **OPIK-6598** | `annotationQueue` fixture, depends on `project` + traces | `POST /annotation-queues` (with trace IDs) | `AnnotationQueuesPage` (list + create), `AnnotationQueuePage` (per-queue reviewer view with score entry / reason / skip / save) | **T2, not T1** — tag `@t2-cuj`, not `@t1-smoke`. Multi-step UI flow with persistence across two entity types (queue + source traces). Verify feedback scores landed on the source traces via `sdkClient.python` after UI scoring. SDK exports are `opik.TracesAnnotationQueue` (Python top-level) — use that, not the rest_api internals. |

# Standing rules (apply to every phase)

Most of these are auto-loaded from `.claude/rules/*.md`. Restating the critical ones here:

- **`playwright-pom-discovery` skill is mandatory before any POM selector work.** The `.claude/rules/playwright-pom.md` rule enforces this. Phase 3 invokes the skill explicitly.
- **CUJ retro lessons from OPIK-6128.** Bundled with the skill at `.agents/skills/playwright-pom-discovery/retro-lessons.md`. Read those before writing the spec. Key points: UI-first assertions by default; FE testid in same PR; spec case counts are tentative; inspection methods on `backendClient` are pre-blessed.
- **Public SDK surface only.** No deep imports into `opik/rest_api/*`. Use `new Opik({...}).api.*` for the typed REST surface from TS; use `opik.Opik(...)` / decorators for Python.
- **SDK-first, REST-fallback.** State creation through `sdkClient.python` (bridge) or `sdkClient.typescript` (direct). REST via `backendClient` is inspection + teardown only.
- **No meta-smoke tests.** Don't create `<entity>-lifecycle.spec.ts` that tests fixture wiring in isolation. The CUJ test itself validates the fixture.
- **No ticket numbers in source comments.** Git blame surfaces history.
- **No GPG signing.** Use `git -c commit.gpgsign=false commit ...` on every commit.
- **Per-ticket work-log docs are local-only.** When you write a spec, plan, or discovery report for THIS ticket, put it under `docs/superpowers/specs/`, `docs/superpowers/plans/`, `docs/superpowers/discovery/` respectively. Never `git add` anything under `docs/superpowers/`. Verify before each commit via `git status` and after the final push via `git log origin/main..HEAD --stat -- 'docs/superpowers/'` (must be empty). Reusable team artifacts (this prompt, the rules, the skill, the AGENTIC.md doc) DO live in the tracked repo — don't confuse them with per-ticket work-logs.
- **Worktree pattern.** `.worktrees/<branch-name>`. Already in `.git/info/exclude`. Worktree the work off `origin/main`.

# Phase 0 — Setup (no gate)

1. Read the `TICKET:` line above. If it's a Jira key (`OPIK-XXXX`), proceed via Path A (ticket-anchored, this prompt). If it's blank, says "no ticket", or contains a natural-language description, **stop following this prompt** and invoke the `playwright-pom-discovery` skill — it has a Path B entry point that handles scoping for ticketless work. Don't try to ask the user to file a ticket; that's the skill's job to handle (or skip).
2. Fetch the Jira ticket description via the Atlassian MCP (`mcp__claude_ai_Atlassian__getJiraIssue`, cloud id `70d91365-5d13-4b02-b353-de8a8e8ee962`). Read it.
3. Read the three required files listed in "What you're walking into" above.
4. Create the worktree:
   ```
   git fetch origin
   git worktree add -b andreic/<TICKET-KEY>-<short-slug> .worktrees/<TICKET-KEY>-<short-slug> origin/main
   cd .worktrees/<TICKET-KEY>-<short-slug>
   ```
   Short slug examples: `dataset-crud`, `test-suites`, `experiments`, `online-eval`, `annotation-queue`.
5. Run `npx tsc --noEmit` from `tests_end_to_end/e2e/` to confirm baseline is clean.

# Phase 1 — Spec (STOP for user gate)

Write a spec at `docs/superpowers/specs/<DATE>-<TICKET-KEY-LOWERCASE>-<short-slug>-design.md` (local-only, never `git add`). Date format: `YYYY-MM-DD`.

Use OPIK-6128's PR (#6823) as the spec-shape reference — read its description and the test file it shipped. Mirror the section structure for your ticket; swap the scope. Sections expected:

- §1 Why this exists
- §2 Scope (in / out / explicit non-decisions)
- §3 Architectural fit
- §4 Component design (bridge route, SDK method, fixture, POMs, test file shape). Do NOT include a "matchers" subsection by default — see the assertion-style guidance below.
- §5 Phased delivery — direct port of OPIK-6128's §5
- §6 Pre-work / shared resources — direct port
- §7 Acceptance criteria
- §8 Risks and mitigations
- §9 Non-scope reminders

**Critical things to get right in §4 (component design):**

- **Test case count is TENTATIVE.** Propose 2-3 cases for the UI-create+SDK-verify pair (when applicable) or 2 cases for SDK-only journeys. If you suspect a case is redundant, name it and propose collapsing.
- **Don't pre-specify custom Jest/Playwright matchers** (`expect.extend(...)` registrations under `matchers/register.ts`). Default assertion style is UI-first using Playwright's built-in locator assertions: `await expect(panel.spansCountLabel(1)).toBeVisible()`, `await expect(logs.row(name)).toHaveCount(1)`, `await expect(panel.errorBadge).toBeHidden()`. These are sufficient for everything the data-plane CUJ suite needs.

  Touching `matchers/register.ts` is an explicit decision, not a default: do it only if you've identified a specific assertion the test needs that the built-in locator assertions genuinely can't express. If you do touch it, the assertion must be USED by the test you ship — OPIK-6128's six dead matchers are the cautionary tale (none consumed in the final test). The current state of `matchers/register.ts` is dead code from OPIK-6128; you're allowed to ignore it.
- **Identify FE `data-testid`s likely needed.** Best guess based on the cheat-sheet POM list and the OPIK-6128 reference. Phase 3 confirms via live inspection.
- **For OPIK-6595, OPIK-6137, OPIK-6598:** both SDK-create and UI-create tests in the spec. For OPIK-6596 and OPIK-6597: SDK-create only (UI-create deferred to T2).

Report back when the spec is written:

- Spec file path.
- 1-paragraph summary of scope: "this ticket adds X bridge route, Y fixture, Z POMs, with N test cases asserting [...]."
- Any design decisions you're uncertain about, called out explicitly.

**STOP.** Do not start Phase 2 until the user reads the spec and approves.

# Phase 2 — Bridge route + SDK client method + fixture

Only after Phase 1 spec approval.

Scope per the spec's §4. Verification gate:

- `npx tsc --noEmit` clean.
- Scratch script (NOT committed) that creates the entity via the new bridge route against staging, verifies it's visible via the appropriate `backendClient` method (add the inspection method to `backendClient` if needed — per the retro lessons, this is pre-blessed), then deletes the parent (which cascades). Report the actual command + actual stdout, not a narrative.
- Delete the scratch script before commit.

Use the `superpowers:test-driven-development` skill for the scratch verification (write the scratch first, watch it fail because the route doesn't exist, implement, watch it pass).

Commits at this phase, in order:
- `feat(bridge): add POST /<route> route`
- `feat(sdk): add <methodName> on PythonSdkClient`
- `feat(fixtures): add <entity> fixture extending failure-artifacts`
- `feat(backend-client): add <inspectionMethod(s)>` (if needed)

Report when done. STOP only if you hit something the spec didn't predict. Otherwise proceed to Phase 3.

# Phase 3 — UI discovery (STOP for user gate)

**Invoke the `playwright-pom-discovery` skill via the `Skill` tool.** The 10-step procedure is in the skill. Don't write any code under `pom/` before completing this phase.

Use a long-lived pinned discovery scratch project in your staging workspace (convention: name it something like `discovery-scratch-pinned`, seed it once with example entities of varied shape, leave it long-lived for reuse). The user will tell you the project name and workspace at kickoff if you need to confirm. If your CUJ needs entity shapes that aren't in the pinned project, seed additional state via Phase 2's bridge route. Do not click-create state through the UI for discovery setup — UI-create is what the test exercises, not what the discovery uses.

Storage state for the browser MCP: `global-setup` will mint `.auth/user.json` for you the first time you run a Playwright test, OR you can capture it manually via `npx playwright codegen --save-storage=.auth/user.json https://staging.dev.comet.com/opik/`. The browser MCP loads it via `storageState: '.auth/user.json'` in the navigation config.

Deliverable (THE GATE): a discovery report at `docs/superpowers/discovery/<DATE>-<TICKET-KEY-LOWERCASE>-<short-slug>.md` (local-only). Same shape as a `playwright-pom-discovery` skill output. Must include:

- URLs explored, entity preconditions for each page.
- Per-element table: description, chosen selector, fallback if selector is fragile.
- **List of `data-testid`s missing from the FE that you'll add in this PR.** Per retro lesson #4, default is to add them — flag any element where the chosen selector is brittle (CSS path, body text regex, nth-child, etc.) AND propose a testid name. Don't ship workarounds without paired FE testid additions.
- UI behavior notes that affect test design (pagination defaults, sort order, dialog modal vs side-pane behavior, etc.).

Report back with the path to the discovery report. **STOP.** Do not start Phase 4 until the user reads it and approves.

# Phase 4 — POMs + test file + FE testids

Only after Phase 3 discovery approval.

Scope per the spec's §4. The POMs you write must use the selectors confirmed in the discovery report. FE `data-testid` additions go in THIS PR (cross-package, QA has permission per parent design §10.4).

**`test.step()` wrapping is mandatory.** Per retro lesson #13 (in `.agents/skills/playwright-pom-discovery/retro-lessons.md`), every test body wraps its logical phases in `await test.step('description', async () => { ... })`, and every POM method wraps its body in a `test.step()` returning the method's result. This is non-negotiable for Allure compatibility (the data-plane suite's eventual reporter) and pays back immediately in `npx playwright show-trace` debugging. Granularity: a "phase" is something you'd describe with a complete sentence ("seed three traces", "open trace and verify panel"). Read lesson #13 in full before writing the test file or POM methods.

Verification gate (use `superpowers:verification-before-completion`):

Run from the worktree's `tests_end_to_end/e2e/` directory:

```bash
OPIK_DEPLOYMENT=cloud \
  OPIK_API_KEY=<staging api key from user> \
  OPIK_BASE_URL=https://staging.dev.comet.com/opik \
  OPIK_TEST_USER_EMAIL=<staging email from user> \
  OPIK_TEST_USER_PASSWORD=<staging password from user> \
  OPIK_WORKSPACE=<your-workspace> \
  OPIK_TEST_USER_NAME=<your-workspace> \
  WORKERS=1 \
  npx playwright test tests/_seed/ tests/<feature-dir>/ --reporter=list
```

Expected: ALL tests pass — OPIK-6499 `sdk-clients.spec.ts`, OPIK-6128 `trace-explore-smoke.spec.ts`, and your new tests. Confirm `[WebServer] INFO: Uvicorn running on http://127.0.0.1:5175` in stdout (proves bridge auto-spawned). After the run, confirm zero orphans:

```bash
curl -sS "https://staging.dev.comet.com/opik/api/v1/private/projects?name=cuj-<RUN_ID>-" \
  -H "Authorization: $OPIK_API_KEY" -H "Comet-Workspace: <your-workspace>" | jq '.total'
```

Should print `0`.

**REPORT THE ACTUAL STDOUT VERBATIM.** Not a summary. Not "tests passed." The user reads the output to verify the gate. If the chat can't hold the output, save to `/tmp/<TICKET-KEY>-phase4.log` and tell the user the path.

If any test fails, debug it. Don't proceed to Phase 5 with red tests.

# Phase 5 — PR

Push the branch:

```bash
git push -u origin andreic/<TICKET-KEY>-<short-slug>
```

Verify no `docs/superpowers/` files in the diff:

```bash
git log origin/main..HEAD --stat -- 'docs/superpowers/'
```

Must print nothing. If anything appears, STOP and remove the file(s) from the branch.

Open the PR via the `comet:create-pr` skill. Title format: `[<TICKET-KEY>] [QA] feat: <short summary>`. Body shape follows OPIK-6128's PR (#6823). Include:

- The Phase 4 stdout verbatim as evidence.
- A summary of FE `data-testid` additions, if any.
- Cross-link to OPIK-6128 / OPIK-6500 if relevant.
- AI watermark filled in.

After the PR is open, transition the Jira ticket to "In Review" via Atlassian MCP. Post a progress comment to the Jira ticket using REAL newlines in the body (NOT literal `\n` strings — per the `feedback_jira_comment_newlines` memory).

Report PR URL + Jira comment URL back to the user. Then stop.

# When you're stuck

Escalate (don't silently work around) if:

- The Jira ticket description disagrees with this prompt's cheat-sheet line.
- The pinned discovery scratch project is missing or empty on staging.
- Storage state minting fails in `global-setup`.
- Phase 3 reveals the page is structured fundamentally differently from what the spec assumed.
- FE owners decline cross-package testid PRs and the fallback selector strategy isn't viable.
- A Phase 4 staging run fails for a reason that isn't selector-fixable.
- You notice an adjacent file needs improvement that's tempting to bundle into this PR (per retro lesson #6, file a separate ticket instead).

Don't proceed past a failing or unclear gate. Don't compress the 5-phase model. Don't expand scope.

# Reference

- OPIK-6128 PR for shape: https://github.com/comet-ml/opik/pull/6823
- OPIK-6500 PR for fixture pattern: https://github.com/comet-ml/opik/pull/6770
- OPIK-6499 PR for bridge-route pattern: https://github.com/comet-ml/opik/pull/6768
- Architectural-decision references: PR #6677 (OPIK-6126 foundation), PR #6770 (OPIK-6500 base fixtures), PR #6823 (OPIK-6128 first CUJ), PR #6851 (OPIK-6595 Dataset CRUD with UI-create pair) — read the descriptions on these to absorb the architectural decisions.
- `playwright-pom-discovery` skill: invoked via the `Skill` tool by name
- Retro lessons rule: `.agents/skills/playwright-pom-discovery/retro-lessons.md` (bundled with the skill)
- POM discovery rule: `.claude/rules/playwright-pom.md` (bundled with the skill)

# Credentials

The user will provide staging credentials in their next message after pasting this prompt. Expected env vars:

- `OPIK_API_KEY` — staging API key
- `OPIK_TEST_USER_EMAIL` — login email
- `OPIK_TEST_USER_PASSWORD` — login password
- `OPIK_WORKSPACE` — your staging workspace name
- `OPIK_BASE_URL` — currently `https://staging.dev.comet.com/opik`

Do not log these credentials anywhere they could be captured (PR descriptions, Jira comments, commit messages, file contents). They're for runtime use during Phase 2 and Phase 4 verification only.

**Defensive fix that should ride along with this PR if it hasn't landed yet:** `tests_end_to_end/e2e/.gitignore` is missing entries for `.env.cloud` and `.env.local` (the real-credentials files; the `.example` templates are checked in but the real files aren't gitignored). Check `git show origin/main:tests_end_to_end/e2e/.gitignore | grep -E '\\.env'` — if there's no `.env` line, add the 2-line entry to your branch's `.gitignore`:

```
# Local credentials (templates checked in, real values gitignored)
.env.local
.env.cloud
```

Include in Phase 5 PR as a small defensive fix. Per the retro lesson on adjacent refactors: this is load-bearing for the workflow (anyone using the `.env.cloud.example` template needs to know their real file is safe from accidental commit), not adjacent improvement. **Skip if already on main.**
