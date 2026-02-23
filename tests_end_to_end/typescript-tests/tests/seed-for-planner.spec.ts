/**
 * Seed Test for Playwright Test Planner
 *
 * This seed test is used by the Playwright Test Planner agent to bootstrap
 * test generation. It provides:
 *
 * 1. A ready-to-use `page` context connected to the Opik application
 * 2. Access to all fixtures defined in base.fixture.ts (envConfig, helperClient, etc.)
 * 3. An initialized browser session at the Opik frontend URL
 * 4. Navigation to all major sections to verify the app is ready
 *
 * The planner agent will:
 * - Run this test to set up the environment
 * - Use the page context to explore the UI
 * - Generate test scenarios based on user flows
 * - Save test plans to typescript-tests/specs/
 *
 * After planning, the generator agent will use this seed as a template
 * for creating executable tests, following the conventions in:
 * - fixtures/base.fixture.ts (fixture patterns)
 * - page-objects/*.page.ts (page object patterns)
 * - test-conventions.md (coding standards)
 */

import { test, expect } from '../fixtures/base.fixture';

test.describe('Seed Test for Planner', () => {
  test('seed - initialize app and verify all sections are accessible', async ({ page, envConfig }) => {
    const frontendUrl = envConfig.getWebUrl();
    const workspace = envConfig.getConfig().workspace;

    // Navigate to the Opik frontend
    await page.goto(frontendUrl);
    await page.waitForLoadState('domcontentloaded');

    // Verify the app has loaded
    await expect(page).toHaveTitle(/Opik/);
    console.log(`Opik application ready at ${frontendUrl} (workspace: ${workspace})`);

    // Navigate to Projects section
    await page.goto(`${frontendUrl}/${workspace}/projects`);
    await expect(page.getByText('Projects').first()).toBeVisible({ timeout: 10000 });
    console.log('Projects section accessible');

    // Navigate to Datasets section
    await page.goto(`${frontendUrl}/${workspace}/datasets`);
    await expect(page.getByText('Datasets').first()).toBeVisible({ timeout: 10000 });
    console.log('Datasets section accessible');

    // Navigate to Experiments section
    await page.goto(`${frontendUrl}/${workspace}/experiments`);
    await expect(page.getByText('Experiments').first()).toBeVisible({ timeout: 10000 });
    console.log('Experiments section accessible');

    // Navigate to Prompts section
    await page.goto(`${frontendUrl}/${workspace}/prompts`);
    await expect(page.getByText('Prompts').first()).toBeVisible({ timeout: 10000 });
    console.log('Prompts section accessible');

    // Navigate to Playground section
    await page.goto(`${frontendUrl}/${workspace}/playground`);
    await expect(page.getByText('Playground').first()).toBeVisible({ timeout: 10000 });
    console.log('Playground section accessible');

    // Navigate to Configuration section (Feedback Definitions)
    await page.goto(`${frontendUrl}/${workspace}/configuration?tab=feedback-definitions`);
    await expect(page.getByText('Feedback definitions').first()).toBeVisible({ timeout: 10000 });
    console.log('Configuration section accessible');

    console.log('All Opik sections verified and accessible');
  });
});
