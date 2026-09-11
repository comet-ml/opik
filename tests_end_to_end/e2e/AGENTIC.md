# Adding E2E tests with an agent

This suite is built to be extended by a coding agent. You describe the feature you want covered; the agent runs a proven loop and leaves you with a working, locally-verified Playwright test.

## Two ways to start

1. **Type the command:** `/comet:add-e2e-test` — then describe the flow you want covered.
2. **Just ask in plain English:** tell Claude Code "add an e2e test for the experiments comparison page" (or "…for the feature I just built", or "…for this branch"). It routes to the same procedure.

Both land in the `writing-e2e-tests` skill, which carries the full procedure and the suite's conventions.

## What the agent does

A five-step loop — analyze the feature + frontend code → explore the live UI with the Playwright MCP → write the Page Object Model + spec → run it locally until green — with two lightweight checkpoints where it confirms direction with you (scope, and the live-UI discovery findings). The step-by-step is in the `writing-e2e-tests` skill.

## Prerequisites

- Opik running locally (`./opik.sh`, frontend at `http://localhost:5173`). That's the default target.
- The Playwright MCP servers ship in the repo's `.mcp.json` — no setup needed.

## Going deeper

The procedure, conventions, and the map of where files land (`tests/`, `pom/`, `fixtures/`, the SDK bridge) live in the `writing-e2e-tests` skill and its `conventions.md`. The live-UI selector hunt is the `playwright-pom-discovery` skill. See [README.md](README.md) for the environment-variable surface and quick start.
