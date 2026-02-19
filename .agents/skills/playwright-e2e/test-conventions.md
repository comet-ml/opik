# Opik E2E Test Conventions

This document codifies all test writing standards for the Opik E2E test suite. AI agents generating or fixing tests MUST follow these conventions.

## Imports

Always import `test` and `expect` from the appropriate **fixture file**, never from `@playwright/test` directly.

```typescript
// CORRECT - import from fixture
import { test, expect } from '../../fixtures/projects.fixture';

// WRONG - never import directly from playwright
import { test, expect } from '@playwright/test';
```

Choose the fixture file based on the feature being tested:

- Projects: `fixtures/projects.fixture`
- Datasets: `fixtures/datasets.fixture`
- Tracing/Threads: `fixtures/tracing.fixture`
- Feedback/Experiments/Prompts: `fixtures/feedback-experiments-prompts.fixture`
- General (no feature fixtures needed): `fixtures/base.fixture`

## File Structure

```typescript
import { test, expect } from '../../fixtures/{feature}.fixture';
import { SomePage } from '../../page-objects/some.page';

test.describe('Feature Area Tests', () => {
  test.describe('with SDK-created resources', () => {
    test('Description of test @sanity @happypaths @fullregression @featuretag', async ({ page, helperClient, fixtureArg }) => {
      // test body
    });
  });

  test.describe('with UI-created resources', () => {
    test('Description of test @happypaths @fullregression @featuretag', async ({ page, helperClient, fixtureArg }) => {
      // test body
    });
  });
});
```

## Test Naming

Test names MUST be descriptive AND include tags at the end. Tags control which test suites include the test.

```typescript
// Format: 'Human-readable description @tag1 @tag2 @tag3'
test('Projects created via SDK are visible in both UI and SDK @sanity @happypaths @fullregression @projects', ...)
```

### Available Tags

- `@sanity` - Critical smoke tests (run first, should be fast)
- `@happypaths` - Happy path scenarios
- `@fullregression` - Full regression suite (every test should have this)
- Feature tags: `@projects`, `@datasets`, `@experiments`, `@prompts`, `@playground`, `@tracing`, `@threads`, `@attachments`, `@feedbackscores`, `@onlinescores`

### Tag Guidelines

- Every test MUST have `@fullregression` and its feature tag
- Critical CRUD tests should also have `@sanity` and `@happypaths`
- Edge cases and error handling tests may only have `@fullregression` and the feature tag

## Test Annotations

Every test MUST have a description annotation as its first line:

```typescript
test('Test name @tags', async ({ page }) => {
  test.info().annotations.push({
    type: 'description',
    description: `Tests that [what is being tested].

Steps:
1. [First step]
2. [Second step]
3. [Third step]

This test ensures [why this matters].`
  });

  // actual test code follows
});
```

## Test Steps

Use `test.step()` to group related actions into logical steps:

```typescript
await test.step('Verify project is retrievable via SDK', async () => {
  await helperClient.waitForProjectVisible(projectName, 10);
  const projects = await helperClient.findProject(projectName);
  expect(projects.length).toBeGreaterThan(0);
});

await test.step('Verify project is visible in UI', async () => {
  const projectsPage = new ProjectsPage(page);
  await projectsPage.goto();
  await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
});
```

## Page Objects

ALWAYS use page objects for UI interactions. NEVER use raw `page.click()` or `page.fill()` directly in test files.

```typescript
// CORRECT - use page object
const projectsPage = new ProjectsPage(page);
await projectsPage.goto();
await projectsPage.createNewProject(projectName);

// WRONG - raw playwright calls in test
await page.goto('/default/projects');
await page.getByRole('button', { name: 'Create new project' }).click();
```

If a page object method doesn't exist for what you need, consider adding it to the page object file rather than putting raw locators in the test.

## Fixture Usage

Destructure fixtures in the test function arguments:

```typescript
test('Test name', async ({ page, helperClient, createProjectApi }) => {
  // createProjectApi is the project name (string)
  // The fixture created the project before the test runs
  // The fixture will clean it up after the test finishes
});
```

Key rules:

- Fixtures handle resource creation AND cleanup automatically
- If a test needs manual cleanup (e.g., the test renames a resource), use `try/finally`:

```typescript
let nameUpdated = false;
try {
  await helperClient.updateProject(originalName, newName);
  nameUpdated = true;
  // ... verification ...
} finally {
  if (nameUpdated) {
    await helperClient.deleteProject(newName);
  }
}
```

## Locator Preferences

Use locators in this priority order:

1. `getByRole()` - semantic, resilient to implementation changes
2. `getByTestId()` - explicit test identifiers
3. `getByText()` - visible text content
4. `getByPlaceholder()` - form inputs
5. `getByLabel()` - form labels
6. CSS/XPath selectors - last resort only

```typescript
// BEST - role-based
page.getByRole('button', { name: 'Create project' })
page.getByRole('cell', { name: projectName, exact: true })

// GOOD - test IDs
page.getByTestId('search-input')

// ACCEPTABLE - text/placeholder
page.getByText(projectName).first()
page.getByPlaceholder('Project name')

// AVOID - CSS selectors (fragile)
page.locator('.project-row .delete-btn')
```

## Waiting Strategies

### For UI state changes

Use Playwright's auto-waiting assertions:

```typescript
// CORRECT - auto-waits with timeout
await expect(page.getByText(name)).toBeVisible({ timeout: 5000 });
await expect(page.getByText(name)).not.toBeVisible();

// WRONG - manual timeout
await page.waitForTimeout(3000);
expect(await page.getByText(name).isVisible()).toBe(true);
```

### For SDK operations (eventual consistency)

Use the helperClient wait methods:

```typescript
await helperClient.waitForProjectVisible(projectName, 10);    // retries
await helperClient.waitForDatasetVisible(datasetName, 10);
await helperClient.waitForTracesVisible(projectName, count, 30);
```

### Avoid

- `page.waitForLoadState('networkidle')` - unreliable, deprecated pattern
- `page.waitForTimeout(n)` in assertions - use `expect().toBeVisible({ timeout: n })` instead
- Hard-coded delays > 2 seconds
- `page.$()` or `page.$$()` - legacy selectors, use locators instead

## Cleanup

- **Default**: Let fixtures handle cleanup (they run teardown even on test failure)
- **Manual cleanup needed when**: the test modifies a resource name, or creates resources not managed by fixtures
- **Always use try/finally** for manual cleanup to ensure it runs on failure

## Test Data

- Use random names for resources: `generateProjectName()`, `generateDatasetName()` from `helpers/random.ts`
- Fixtures auto-generate random names via `projectName` and `datasetName` fixtures
- Never use hard-coded resource names that might collide across test runs

## File Organization

```
tests/
├── projects/          # Project CRUD tests
├── datasets/          # Dataset CRUD and item tests
├── experiments/       # Experiment CRUD and item tests
├── prompts/           # Prompt CRUD and versioning tests
├── tracing/           # Trace, span, thread, attachment tests
├── feedback-scores/   # Feedback definition CRUD tests
├── playground/        # Playground interaction tests
├── online-scoring/    # Online scoring rule tests
└── seed-for-planner.spec.ts  # AI agent seed test
```

Place new tests in the appropriate feature directory. Create a new directory if testing a new feature area.
