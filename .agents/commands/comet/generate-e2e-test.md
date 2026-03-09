# Generate E2E Test

## Overview

Generate a comprehensive Playwright E2E test for an Opik feature using the three-agent workflow: Plan -> Generate -> Heal.

---

## Prerequisites

- Opik must be running locally (`http://localhost:5173`)
- The `playwright-test` MCP server must be connected
- Playwright must be installed: `cd tests_end_to_end/typescript-tests && npm install && npx playwright install chromium`

---

## Safety: Verify Local Config

**Before doing anything else**, check `~/.opik.config` and ensure it points to localhost. The Python Opik SDK (used by the Flask test helper service) reads this file, and if it points to a cloud environment, the agent will accidentally create real data there.

Run this command to check:
```bash
cat ~/.opik.config
```

If `url_override` points to anything other than `http://localhost:5173/api`, back up the config and set it to local:

```bash
cp ~/.opik.config ~/.opik.config.bak 2>/dev/null || true

cat > ~/.opik.config << 'EOF'
[opik]
url_override = http://localhost:5173/api
workspace = default
EOF
```

**After the test generation workflow is complete, remind the user to restore their original config:**
```bash
cp ~/.opik.config.bak ~/.opik.config
```

If `~/.opik.config` already points to localhost, skip this step.

---

## Inputs

- **Feature to test** (required): A description of what to test. The more specific you are, the better the output.

### What makes a good input

**A thorough plain-English description is the best input.** Be specific about:
- Which feature area (e.g., "prompts", "datasets", "experiments")
- Which user flows to cover (e.g., "create, edit, delete", "versioning", "search and filter")
- Any edge cases you care about (e.g., "empty state", "duplicate names", "special characters")
- Whether to focus on happy paths, error cases, or both

**Good examples:**
- "Generate tests for the prompt versioning flow: creating a prompt, editing it to create a new version, switching between versions in the UI, and verifying version history"
- "Test dataset item CRUD - adding items manually, editing cell values inline, deleting individual items and bulk delete, and verify counts update correctly"
- "Cover the experiment comparison page: run two experiments on the same dataset, open the comparison view, verify columns show both experiment results side by side"

**Optional extra context you can provide:**
- **GitHub PR URL or number**: If the feature was recently added/changed, point to the PR so the agent can read the diff and understand what changed (e.g., "PR #5300 added a new feedback score column")
- **Jira ticket**: Reference a ticket for requirements context (e.g., "OPIK-3500 describes the acceptance criteria")
- **Specific UI page URL**: Point to exactly where the feature lives (e.g., "the page at localhost:5173/default/projects/123/experiments")
- **Existing test to extend**: If you want to add scenarios to an existing test file (e.g., "add error cases to tests/prompts/prompts.spec.ts")

The agent will always explore the live UI regardless of what extra context you provide — the description just helps it know where to focus.

---

## Instructions

**Read the skill definition at `skills/playwright-e2e/SKILL.md` and follow it exactly.**

The skill contains:
- The knowledge base (context documents for domain, conventions, page objects, fixtures)
- The three-phase workflow (Planning, Generation, Healing) with specific agent prompts
- References to the `playwright-test` MCP server tools that MUST be used

**You MUST use the `playwright-test` MCP tools** (`planner_setup_page`, `generator_setup_page`, `playwright_test_run_test`, `playwright_test_debug_test`, etc.) to interact with the running Opik application. Do NOT just read code files — the agents are designed to explore the live UI via a real browser.

### Workflow Summary

1. **Verify config** — check `~/.opik.config` points to localhost (see Safety section above)
2. **Read** `skills/playwright-e2e/SKILL.md` and all linked knowledge base documents
3. **Plan** using the planner agent prompt (`skills/playwright-e2e/agents/playwright-test-planner.md`) and `planner_setup_page` MCP tool
4. **Generate** using the generator agent prompt (`skills/playwright-e2e/agents/playwright-test-generator.md`) and `generator_setup_page` MCP tool
5. **Heal** using the healer agent prompt (`skills/playwright-e2e/agents/playwright-test-healer.md`) and `playwright_test_run_test` / `playwright_test_debug_test` MCP tools
6. **Review** — present the generated test files, highlight any `test.fixme()` tests, suggest additional scenarios
7. **Remind user** to restore `~/.opik.config` if it was modified

---

## Success Criteria

1. Test plan covers the requested feature with both SDK and UI scenarios
2. Generated tests follow all conventions from `test-conventions.md`
3. Tests use existing page objects and fixtures (not raw Playwright calls)
4. All tests pass (or are marked `test.fixme()` with explanation)
5. Tests include proper tags, annotations, and step grouping

---

## Notes

- If the `playwright-test` MCP server is not connected, STOP and tell the user to connect it before proceeding
- The Flask test helper service starts automatically via Playwright's `webServer` config
- Generated tests should be reviewed by a human before merging
- If new page object methods are needed, add them to the page object file
- If `data-testid` attributes are needed for reliable locators, add them to the React components in `apps/opik-frontend/src/`

---

**End Command**
