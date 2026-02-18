---
name: playwright-e2e
description: Playwright E2E test generation workflow for Opik. Use when generating, fixing, or planning automated tests in tests_end_to_end/.
---

# Playwright E2E Test Workflow

Three-agent workflow for generating end-to-end tests for the Opik application:

1. **Planner** -> Explores the Opik UI, generates a markdown test plan with fixture/page-object references
2. **Generator** -> Transforms the plan into executable Playwright tests following Opik conventions
3. **Healer** -> Automatically fixes failing tests with Opik-specific debugging knowledge

## When to Use

Use when developer requests E2E test generation or repair:
- "Generate an E2E test for the new dashboard"
- "Create automated test for the upload flow"
- "Add happy path test for experiment creation"
- "Fix the failing prompts test"

## Prerequisites

```bash
# Opik must be running locally
# Frontend at http://localhost:5173
# Backend healthy

# Playwright environment
cd tests_end_to_end/typescript-tests
npm install
npx playwright install chromium
```

## Knowledge Base

These context files provide the domain knowledge agents need to produce accurate tests:

- **[opik-app-context.md](opik-app-context.md)** - Opik entities, URL structure, dual SDK/UI pattern, Flask bridge architecture
- **[test-conventions.md](test-conventions.md)** - Import patterns, naming, tags, annotations, locator preferences, waiting strategies
- **[page-object-catalog.md](page-object-catalog.md)** - All page objects with method signatures and constructor info
- **[fixture-catalog.md](fixture-catalog.md)** - Fixture hierarchy, types, cleanup behavior, import paths

## Workflow Phases

### Phase 1: Planning
**Agent**: [agents/playwright-test-planner.md](agents/playwright-test-planner.md)
**Input**: Running Opik app + feature description
**Output**: `tests_end_to_end/typescript-tests/specs/{feature-name}.md`

Uses seed test: `tests/seed-for-planner.spec.ts`

### Phase 2: Generation
**Agent**: [agents/playwright-test-generator.md](agents/playwright-test-generator.md)
**Input**: Markdown test plan from `specs/`
**Output**: `tests_end_to_end/typescript-tests/tests/{feature-area}/{test-name}.spec.ts`

Uses existing fixtures and page objects. References test-conventions.md for all coding standards.

### Phase 3: Healing
**Agent**: [agents/playwright-test-healer.md](agents/playwright-test-healer.md)
**Input**: Failing test + error info
**Output**: Passing test or `test.fixme()` if the feature is genuinely broken

## Directory Structure

```
tests_end_to_end/
├── test-helper-service/        # Flask service bridging TS tests to Python SDK
├── test_files/                 # Test attachments (images, audio, PDFs)
├── installer_utils/            # Shell scripts for environment checks
└── typescript-tests/
    ├── specs/                  # Markdown test plans (planner output)
    ├── tests/                  # Executable tests (generator output)
    │   ├── projects/
    │   ├── datasets/
    │   ├── experiments/
    │   ├── prompts/
    │   ├── tracing/
    │   ├── feedback-scores/
    │   ├── playground/
    │   ├── online-scoring/
    │   └── seed-for-planner.spec.ts
    ├── fixtures/               # Test fixtures (base -> feature-specific)
    ├── page-objects/           # Page Object Model classes
    ├── helpers/                # TestHelperClient, random, wait utilities
    ├── config/                 # Environment configuration
    └── playwright.config.ts    # Playwright configuration
```
