# Adding E2E tests with an agent

This suite is built to be extended by a coding agent. You describe the feature you want covered; the agent runs a proven loop and leaves you with a working, locally-verified Playwright test.

## Two ways to start

1. **Type the command:** `/add-e2e-test` — then describe the flow you want covered.
2. **Just ask in plain English:** tell Claude Code "add an e2e test for the experiments comparison page" (or "…for the feature I just built", or "…for this branch"). It routes to the same procedure.

Both land in the `writing-e2e-tests` skill, which carries the full procedure and the suite's conventions.

## What the agent does

A five-step loop, with two lightweight checkpoints where it confirms direction with you:

1. **Scope** *(checkpoint)* — works out the flow, the page, the target (local by default), and the tags; confirms in one line.
2. **Analyze** — reads the feature's frontend code and figures out what state the page needs to render real data.
3. **Discover the live UI** *(checkpoint)* — explores the running page with the Playwright MCP, picks stable selectors, flags any missing `data-testid`s; confirms before writing code.
4. **Write** — the Page Object Model + spec, plus any `data-testid` the frontend was missing.
5. **Run until green** — runs the new test locally, reads failure traces, fixes, and re-runs until it passes.

## Where things land

- Specs: `tests/<feature>/<name>.spec.ts`
- Page Object Models: `pom/<name>.page.ts`
- Fixtures (seed + teardown): `fixtures/<name>.fixture.ts`
- SDK bridge (auto-spawned during a run): `services/opik-sdk-driver/`

## Prerequisites

- Opik running locally (`./opik.sh`, frontend at `http://localhost:5173`). That's the default target.
- The Playwright MCP servers ship in the repo's `.mcp.json` — no setup needed.

## Going deeper

The procedure and conventions live in the `writing-e2e-tests` skill (`conventions.md` alongside it covers `test.step()` wrapping, assertion style, selector preference, seeding, and tags). The live-UI selector hunt is the `playwright-pom-discovery` skill. See [README.md](README.md) for the environment-variable surface and quick start.
