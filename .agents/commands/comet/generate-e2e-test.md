# Generate E2E Test

## Overview

Generate a comprehensive Playwright E2E test for an Opik feature using the three-agent workflow: Plan -> Generate -> Heal.

This workflow will:

- Explore the Opik UI to understand the feature under test
- Generate a structured test plan with fixture and page object references
- Transform the plan into an executable Playwright test following all Opik conventions
- Run the generated test and automatically fix any failures
- Produce a test that is ~90% complete and ready for human review

---

## Prerequisites

- Opik must be running locally (`http://localhost:5173`)
- Playwright must be installed: `cd tests_end_to_end/typescript-tests && npm install && npx playwright install chromium`

---

## Inputs

- **Feature to test** (required): Description of the feature or user flow to generate tests for (e.g., "prompt versioning", "dataset item CRUD", "experiment creation")

---

## Steps

### 1. Understand the Feature

- Read the relevant skill context files:
  - `skills/playwright-e2e/opik-app-context.md` - Domain knowledge
  - `skills/playwright-e2e/test-conventions.md` - Coding standards
  - `skills/playwright-e2e/page-object-catalog.md` - Available page objects
  - `skills/playwright-e2e/fixture-catalog.md` - Available fixtures
- Check if tests already exist for this feature in `tests_end_to_end/typescript-tests/tests/`
- Identify which page objects and fixtures are relevant

### 2. Plan (Planner Agent)

- Use the **playwright-test-planner** agent (from `skills/playwright-e2e/agents/`)
- Input: Feature description + seed test (`tests/seed-for-planner.spec.ts`)
- The planner will explore the Opik UI and produce a test plan
- Output: Saved to `tests_end_to_end/typescript-tests/specs/{feature-name}.md`

### 3. Generate (Generator Agent)

- Use the **playwright-test-generator** agent (from `skills/playwright-e2e/agents/`)
- Input: The markdown test plan from `specs/`
- The generator will create executable Playwright tests following all Opik conventions
- Output: Test files in `tests_end_to_end/typescript-tests/tests/{feature-area}/`

### 4. Heal (Healer Agent)

- Use the **playwright-test-healer** agent (from `skills/playwright-e2e/agents/`)
- Run the generated tests
- If any tests fail, the healer will:
  - Debug each failure
  - Diagnose root cause (selector change, timing, assertion)
  - Fix the test code
  - Re-run to verify
- Output: Passing tests (or `test.fixme()` for genuinely broken features)

### 5. Review

- Present the generated test files to the user
- Highlight any tests marked as `test.fixme()` that may indicate application issues
- Suggest additional edge cases or scenarios if applicable

---

## Success Criteria

1. Test plan covers the requested feature with both SDK and UI scenarios
2. Generated tests follow all conventions from `test-conventions.md`
3. Tests use existing page objects and fixtures (not raw Playwright calls)
4. All tests pass (or are marked `test.fixme()` with explanation)
5. Tests include proper tags, annotations, and step grouping

---

## Notes

- This workflow requires the Playwright and playwright-test MCP servers to be configured
- The Flask test helper service starts automatically via Playwright's `webServer` config
- Generated tests should be reviewed by a human before merging
- If new page object methods are needed, the generator should add them to the page object file
- If new fixtures are needed, they should follow the existing fixture hierarchy pattern

---

**End Command**
