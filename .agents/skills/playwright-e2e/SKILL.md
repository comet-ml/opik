---
name: playwright-e2e
description: Playwright E2E test generation workflow. Use when generating automated tests in tests_end_to_end/.
---

# Playwright E2E Test Workflow

Three-agent workflow for generating end-to-end tests:

1. **Planner** → Explores UI, generates markdown test plan
2. **Generator** → Transforms plan into executable Playwright tests
3. **Healer** → Automatically fixes failing tests

## When to Use

Use when developer requests E2E test generation:
- "Generate an E2E test for the new dashboard"
- "Create automated test for the upload flow"
- "Add happy path test for experiment creation"

## Prerequisites

```bash
# Opik must be running locally
./scripts/dev-runner.sh --start
curl http://localhost:5173  # verify

# Playwright environment
cd tests_end_to_end/typescript-tests
npm install
npx playwright install chromium
```

## Workflow Phases

### Phase 1: Planning
**Input**: Running app + feature description
**Output**: `tests_end_to_end/typescript-tests/specs/{feature-name}.md`

Uses seed test: `tests/seed-for-planner.spec.ts`

### Phase 2: Generation
**Input**: Markdown test plan from `specs/`
**Output**: `tests_end_to_end/typescript-tests/tests/{feature-area}/{test-name}.spec.ts`

Uses existing fixtures and page objects.

### Phase 3: Healing
**Input**: Generated test + failure info
**Output**: Passing test or `test.fixme()` if feature is broken

## Directory Structure

```
tests_end_to_end/
├── test-helper-service/     # Flask helper for SDK operations
├── installer_utils/         # Shell scripts for installation
└── typescript-tests/
    ├── specs/               # Markdown test plans (planner output)
    ├── tests/               # Executable tests (generator output)
    ├── fixtures/            # Test fixtures
    ├── page-objects/        # Page objects
    └── helpers/             # Helper utilities
```

## Reference Files
- [agents/playwright-test-planner.md](agents/playwright-test-planner.md)
- [agents/playwright-test-generator.md](agents/playwright-test-generator.md)
- [agents/playwright-test-healer.md](agents/playwright-test-healer.md)
