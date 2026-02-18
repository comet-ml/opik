# TypeScript E2E Tests for Opik

This directory contains end-to-end tests for Opik written in TypeScript using Playwright, migrated from the original Python tests.

## Features

- **Test Suite Organization**: Tests are organized with tags similar to pytest markers
- **Suite Selection**: Run specific test suites using tags (@sanity, @fullregression, @projects, @datasets, etc.)
- **Allure Reporting**: Full integration with Allure TestOps for rich test reporting
- **CI/CD Integration**: GitHub Actions workflow for automated testing

## Test Tags

Tests are tagged using Playwright's `@tag` syntax in test names. Available tags:

- `@sanity` - Basic smoke tests for critical functionality
- `@fullregression` - Full regression test suite
- `@projects` - Project-related tests
- `@datasets` - Dataset CRUD operations
- `@experiments` - Experiment functionality
- `@prompts` - Prompt management tests
- `@playground` - AI model playground tests
- `@tracing` - Trace creation and management
- `@threads` - Thread/conversation tests
- `@attachments` - Attachment handling tests

## Running Tests Locally

### Prerequisites

1. Install dependencies:
```bash
cd tests_end_to_end/tests_end_to_end_ts/typescript-tests
npm install
npx playwright install chromium
```

2. Start the test helper service (in a separate terminal):
```bash
cd tests_end_to_end/tests_end_to_end_ts/test-helper-service
python app.py
```

3. Ensure Opik is running locally (see main README)

### Run All Tests
```bash
npm test
```

### Run Specific Test Suites
```bash
npm run test:sanity       # Run only sanity tests
npm run test:regression   # Run full regression suite
npm run test:projects     # Run project tests
npm run test:datasets     # Run dataset tests
npm run test:experiments  # Run experiment tests
npm run test:prompts      # Run prompt tests
npm run test:playground   # Run playground tests
npm run test:tracing      # Run tracing tests
npm run test:threads      # Run thread tests
npm run test:attachments  # Run attachment tests
```

### Other Useful Commands
```bash
npm run test:ui           # Run tests in UI mode
npm run test:headed       # Run tests in headed browser
npm run test:debug        # Run tests in debug mode
npm run test:report       # Show HTML report
npm run allure:report     # Generate and open Allure report
```

## Allure Reporting

### Local Allure Reports

After running tests, generate an Allure report:

```bash
npm run allure:report
```

This will:
1. Generate the Allure report from `allure-results/`
2. Open the report in your browser

### CI/CD Allure Integration

The GitHub Actions workflow automatically:
- Generates Allure results during test execution
- Uploads results to Allure TestOps (if configured)
- Stores results as artifacts for later analysis

## CI/CD Usage

### Triggering Tests via GitHub Actions

The workflow can be triggered in two ways:

1. **Manual Dispatch**: Go to Actions → "Application E2E Tests (TypeScript)" → Run workflow
   - Select the test suite to run (sanity, regression, projects, etc.)

2. **Pull Request**: Automatically runs on PRs that modify:
   - OpenAPI spec: `sdks/code_generation/fern/openapi/openapi.yaml`
   - TypeScript tests: `tests_end_to_end/tests_end_to_end_ts/**`

### Workflow Configuration

The workflow (`../.github/workflows/end2end_suites_typescript.yml`) includes:
- Node.js 20 setup
- Python 3.12 setup (for test helper service)
- Opik installation and startup
- Test execution with suite selection
- Allure reporting integration
- Artifact uploads for reports

## Test Structure

```
typescript-tests/
├── tests/                    # Test files organized by feature
│   ├── projects/
│   ├── datasets/
│   ├── experiments/
│   ├── prompts/
│   ├── playground/
│   ├── tracing/
│   └── feedback-scores/
├── page-objects/            # Page object models
├── fixtures/                # Playwright fixtures
├── helpers/                 # Helper utilities
├── config/                  # Configuration files
├── playwright.config.ts     # Playwright configuration
└── package.json            # Dependencies and scripts
```

## Adding New Tests

When adding new tests:

1. Add appropriate tags to the test name:
```typescript
test('should do something @fullregression @projects', async ({ page }) => {
  // test code
});
```

2. For sanity tests (critical smoke tests), add `@sanity` tag:
```typescript
test('should verify basic functionality @sanity @fullregression @projects', async ({ page }) => {
  // test code
});
```

3. Tests can have multiple tags for flexible suite selection

## Generating Tests with AI Agents

You can automatically generate E2E tests using Playwright's agent capabilities. This workflow uses three specialized agents to explore your UI, create test plans, and generate executable tests.

### Quick Start

**Prerequisites:**
1. Ensure Opik is running locally at `http://localhost:5173`
2. Complete your feature implementation (frontend + backend)

**Generate a test:**

Use Cursor to request test generation:
```
"Generate an E2E test for the [feature name] I just built"
```

Example prompts:
- "Generate an E2E test for the new project metrics dashboard"
- "Create automated test for the dataset upload flow"
- "Add happy path test for experiment comparison"

### The Three Agents

#### 🎭 Planner Agent
Explores your running application and generates a detailed markdown test plan.

**What it does:**
- Navigates through your UI interactively
- Documents user flows and interactions
- Captures expected outcomes
- Saves test plan to `specs/{feature-name}.md`

**Example output:** `specs/project-metrics.md`

#### 🎭 Generator Agent
Transforms the markdown test plan into executable Playwright tests.

**What it does:**
- Reads the test plan from `specs/`
- Generates test code following existing patterns
- Uses appropriate fixtures (projects, datasets, etc.)
- Includes page objects for UI interactions
- Saves test to `tests/{feature-area}/{test-name}.spec.ts`

**Example output:** `tests/projects/project-metrics.spec.ts`

#### 🎭 Healer Agent
Automatically fixes failing tests.

**What it does:**
- Runs the generated test
- Identifies failures (selectors, timing, assertions)
- Updates code to fix issues
- Re-runs until passing or marks as `test.fixme()` if broken

### Generated Test Structure

Agent-generated tests follow the same patterns as manual tests:

```typescript
// spec: specs/feature-name.md
// seed: tests/seed-for-planner.spec.ts

import { test, expect } from '../../fixtures/projects.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';

test.describe('Feature Name @fullregression @feature', () => {
  test('should perform main user flow', async ({ page, projectName }) => {
    // 1. Navigate to starting point
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();

    // 2. Perform user actions
    await projectsPage.clickProject(projectName);

    // 3. Verify expected outcomes
    await expect(page.locator('[data-testid="result"]')).toBeVisible();
  });
});
```

### File Organization

```
typescript-tests/
├── specs/                      # Test plans (generated by planner)
│   ├── project-metrics.md
│   ├── dataset-upload.md
│   └── ...
└── tests/
    ├── seed-for-planner.spec.ts  # Seed test for agents
    ├── projects/
    │   └── project-metrics.spec.ts  # Generated test
    └── ...
```

**Note:** MCP server configuration is canonical in the root `.agents/mcp.json` file (Cursor-compatible path: `.cursor/mcp.json` after `make cursor`).

### Workflow Example

**1. Developer completes a feature:**
```bash
# Feature: Project analytics dashboard
# Files: apps/opik-frontend/src/features/analytics/Dashboard.tsx
```

**2. Developer requests test generation:**
```
I just built a project analytics dashboard. Generate an E2E test for it.
The dashboard shows project statistics. Users access it from the project
details page by clicking the "Analytics" tab.
```

**3. Agents work together:**
- **Planner** explores the UI and creates `specs/project-analytics-dashboard.md`
- **Generator** creates `tests/projects/project-analytics-dashboard.spec.ts`
- **Healer** runs and fixes the test until it passes

**4. Developer reviews and commits:**
```bash
# Review generated files
cat specs/project-analytics-dashboard.md
cat tests/projects/project-analytics-dashboard.spec.ts

# Run the test
npm test -- tests/projects/project-analytics-dashboard.spec.ts

# Commit with feature
git add .
git commit -m "[OPIK-1234] [FE] Add analytics dashboard + E2E test"
```

### Configuration

> **Note:** The `.cursor/` directory is a symlink to `.agents/`. Run `make cursor` from the repository root to set it up.

**Seed Test:** `tests/seed-for-planner.spec.ts`
- Provides a ready-to-use browser context
- Accesses all fixtures and configuration
- Used by agents as a starting point

**Agent Definitions:** `../../.agents/skills/playwright-e2e/agents/`
- `playwright-test-planner.md` - Planner agent configuration
- `playwright-test-generator.md` - Generator agent configuration
- `playwright-test-healer.md` - Healer agent configuration

**MCP Server:** Root `../../.agents/mcp.json` (or `../../.cursor/mcp.json` in Cursor-compatible mode)
- Configures Model Context Protocol server (including `playwright-test` server)
- Enables agent access to Playwright tools

### Best Practices

**When generating tests:**
1. Be specific about what feature to test
2. Start with happy path scenarios
3. Mention related features for context
4. Review generated code before committing

**After generation:**
1. Run the test manually to verify
2. Add appropriate tags (@sanity, @fullregression, etc.)
3. Update test plan if behavior changes
4. Include both spec and test in your PR

### Troubleshooting

**Agent can't access the application:**
```bash
# Ensure Opik is running
curl http://localhost:5173
./scripts/dev-runner.sh --start
```

**Test generation fails:**
- Verify seed test runs successfully: `npm test -- tests/seed-for-planner.spec.ts`
- Check that fixtures and page objects are available
- Ensure spec file is clear and detailed

**Generated test keeps failing:**
- Let the healer agent iterate and fix
- Check if feature actually works manually
- Look for dynamic selectors or timing issues

### Additional Resources

- [Playwright E2E Skill](../../.agents/skills/playwright-e2e/SKILL.md) - Detailed workflow documentation
- [Playwright Test Agents](https://playwright.dev/docs/test-agents) - Official Playwright docs
- [E2E Suite Root Guide](../README.md) - Quick reference

## Comparison with Python Tests

| Feature | Python Tests | TypeScript Tests |
|---------|-------------|------------------|
| Test Framework | pytest | Playwright Test |
| Markers/Tags | `@pytest.mark.sanity` | `@sanity` in test name |
| Suite Selection | `pytest -m sanity` | `TEST_SUITE=sanity playwright test` |
| Reporting | allure-pytest | allure-playwright |
| Language | Python | TypeScript |
| Browser Control | Playwright Python | Playwright |

## Troubleshooting

### Tests not filtering by suite
- Ensure the `TEST_SUITE` environment variable is set
- Check that test names include the correct `@tag` syntax

### Allure reports not generated
- Ensure `allure-playwright` and `allure-commandline` are installed
- Check that `ALLURE_RESULTS` directory exists and has write permissions

### Test helper service not responding
- Ensure the Flask service is running on port 5555
- Check that Python dependencies are installed
- Verify the Opik Python SDK is installed

## Environment Variables

- `OPIK_BASE_URL` - Base URL for Opik (default: http://localhost:5173)
- `OPIK_TEST_WORKSPACE` - Workspace name (default: default)
- `OPIK_TEST_PROJECT_NAME` - Project name for tests
- `OPENAI_API_KEY` - OpenAI API key for playground tests
- `ANTHROPIC_API_KEY` - Anthropic API key for playground tests
- `TEST_SUITE` - Test suite to run (sanity, regression, etc.)
- `ALLURE_RESULTS` - Directory for Allure results (default: allure-results)

## Contributing

When contributing new tests:
1. Follow the existing test structure and patterns
2. Add appropriate tags for suite organization
3. Update this README if adding new test categories
4. Ensure tests pass locally before submitting PR
