# Writing Opik 2.0 E2E Tests with Claude Code

This directory is the Opik 2.0 E2E test suite. The data-plane CUJ tests (Trace Explore, Datasets, Test Suites, Experiments, Prompt Playground, Online Scoring, Annotation Queue) are written using a structured Claude Code workflow.

## TL;DR — two ways to start

### Way 1: just describe what you want, in plain English

Open a fresh **Claude Code** session in the opik repo root and say what you want. For example:

> "Write automated tests in `tests_end_to_end/e2e/` for the agent-config switcher flow."

> "We need E2E coverage for the experiments comparison page."

> "Can you add CUJ tests for online scoring rules?"

The agent picks up an auto-loaded rule (`.claude/rules/playwright-pom.md`) that triggers on phrases like these. It invokes the `playwright-pom-discovery` skill, which scopes the work in 1-5 adaptive questions (what's the happy path, what state needs to exist, what's out of scope), asks if you want it to file a Jira ticket under OPIK-6107 or ship as `[NA]`, drafts a one-pager spec for your approval, then runs the standard 5-phase model end-to-end.

You don't need to know about the kickoff prompt or any files. This is the easy path.

### Way 2: you already have a Jira ticket

Open a fresh Claude Code session. Open `tests_end_to_end/e2e/.agentic/cuj-test-kickoff.md`. Edit the `TICKET:` line at the top to your Jira key (e.g. `TICKET: OPIK-XXXX`). Copy everything from the horizontal rule down, paste into the session. The agent reads the ticket, drafts a spec, runs the 5-phase model.

This is the heavier-context path. Useful when the ticket carries detailed scope that the agent should anchor against.

---

Either way: the agent stops at gates (Phase 1 spec, Phase 3 discovery report) for your review (~5 min each). Expected human time per test: ~15 minutes total. Expected wall-clock: a few hours of agent runtime.

## What's set up for you, in the repo

| Artifact | Path | What it does |
|---|---|---|
| **CUJ workflow skill** | `.agents/skills/playwright-pom-discovery/` (SKILL.md + retro-lessons.md) | The procedure the agent follows on any CUJ ticket: 10-step UI discovery, selector preference order, FE testid policy, plus the cumulative retro lessons from prior tickets (matchers default off, UI-first assertions, `test.step()` mandatory, cascade verification, fixture-seed-shape considerations, etc.). Invoked via the `Skill` tool. |
| **Workflow auto-load rule** | `.claude/rules/playwright-pom.md` | Auto-loads in every Claude Code session under this repo. ~15 lines pointing the agent at the skill above when work touches `tests_end_to_end/e2e/`. |
| **Universal kickoff prompt** | `tests_end_to_end/e2e/.agentic/cuj-test-kickoff.md` | The one prompt you paste per ticket. Reads the Jira ticket, picks the right shape, drives 5 phases (spec → bridge/fixture → UI discovery → POMs+tests → PR). Gates at Phase 1 and Phase 3 for your review. |

You don't need to install or invoke anything separately — Claude Code picks up all of these automatically when you open a session in this repo. The only thing you paste is the kickoff prompt.

## The 5-phase model

The kickoff prompt drives the agent through five phases. You gate the workflow at two of them:

| Phase | Agent does | You |
|---|---|---|
| 1. Plan | Reads Jira ticket + relevant merged PRs + the rules. Drafts a spec under `docs/superpowers/specs/` (your local machine; not committed). | **Read the spec** (~5 min). Approve or redirect. |
| 2. Bridge route + SDK client + fixture | Adds the FastAPI bridge route, the `PythonSdkClient` method, the fixture. Runs a scratch verification against staging. | (auto-proceed; review the verification output if interested) |
| 3. UI discovery | Invokes the POM-discovery skill. Uses Playwright MCP to snapshot the live UI, enumerate testids, propose POM selectors. Drafts a discovery report under `docs/superpowers/discovery/`. | **Read the discovery report** (~5 min). Confirm selectors / approve FE testid additions. |
| 4. POMs + tests + FE testids | Writes the POMs against the confirmed selectors, writes the tests, adds any FE testids needed, runs the full suite against staging. | (auto-proceed; review the staging stdout) |
| 5. PR | Pushes branch, opens PR via `comet:create-pr`, transitions Jira to "In Review". | Review the PR like any other. |

The agent stops between phases. Don't dispatch the next phase yourself — the agent prompts you when each gate needs your input.

## Credentials

The kickoff prompt's last section tells the agent to wait for credentials. After pasting the prompt, your follow-up message provides them. Either:

**Option A (per-session paste):**

```
Staging credentials:

OPIK_API_KEY=<your staging API key>
OPIK_TEST_USER_EMAIL=<your staging email>
OPIK_TEST_USER_PASSWORD=<your staging password>
OPIK_WORKSPACE=<your workspace>
OPIK_TEST_USER_NAME=<your workspace>
OPIK_BASE_URL=https://staging.dev.comet.com/opik
OPIK_DEPLOYMENT=cloud
```

**Option B (persistent `.env.cloud`):** create `tests_end_to_end/e2e/.env.cloud` (gitignored, won't be committed) with the env-var lines, and tell the agent "source .env.cloud before running tests." The agent then runs `set -a; source /full/path/to/tests_end_to_end/e2e/.env.cloud; set +a` before staging verification.

Option B saves typing across multiple tickets; Option A is fine for one-off tickets.

The `.env.cloud.example` file in this directory documents the full env-var shape.

### Credential hygiene (read before your first run)

- `.env.cloud` is gitignored. Never `git add` it. Never copy its contents into a PR description, Jira comment, screenshot, or any chat message outside the Claude Code session you're driving.
- The agent is instructed not to log credentials, but the human in the loop is the last line of defence. If you see a credential about to surface in a PR body or a public artifact, redact it before sending.
- If a staging credential ever leaks (committed, screenshotted, posted publicly), rotate it immediately via the Comet staging admin and tell the team — don't wait to see if anyone noticed.

## Per-ticket work-log conventions

Specs, plans, and discovery reports the agent writes for YOUR ticket are work-logs, not team artifacts. They live under `docs/superpowers/specs/`, `docs/superpowers/plans/`, `docs/superpowers/discovery/` on your local machine and are **gitignored** — they never enter PRs.

The reusable team artifacts (the kickoff prompt, the rules, the skill, this doc) DO live in the tracked repo. The agent knows the difference.

## Asking for help

If the agent gets stuck or surprises you (e.g., proposes scope creep, picks the wrong fixture parent, writes selectors against FE source without inspecting the DOM) — that's a signal worth surfacing. Retro lessons grow from these moments. If you spot a class of mistake that's likely to recur, post in the QA channel; we'll either tighten the rule, sharpen the kickoff prompt, or both.

## What's NOT here yet

This workflow currently covers the **data-plane CUJ tests** (the 6 tickets listed at the top). It does NOT yet cover:

- The agent-side CUJs (Agent Configuration, Playground Pairing, Ollie Instrument, Skills Instrument) — those depend on `goldenAgent` / `localRunner` / `ollieSession` / `claudeCodeAgent` fixtures that aren't built yet. When that work starts, this doc and the kickoff prompt will be updated.
- AI-augmented capabilities (LLM-as-judge matchers, self-healing selectors, AI failure summaries) — Layer B / Layer C work, future scope.

For anything outside the data-plane CUJ scope, fall back to the general Claude Code workflow (skills, brainstorming, etc.) rather than this kickoff prompt.
