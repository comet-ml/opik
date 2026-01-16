/**
 * Seed Test for Playwright Test Planner
 *
 * This seed test is used by the Playwright Test Planner agent to bootstrap
 * test generation. It provides:
 *
 * 1. A ready-to-use `page` context connected to the Opik application
 * 2. Access to all fixtures defined in base.fixture.ts (envConfig, helperClient, etc.)
 * 3. An initialized browser session at the Opik frontend URL
 *
 * The planner agent will:
 * - Run this test to set up the environment
 * - Use the page context to explore the UI
 * - Generate test scenarios based on user flows
 * - Save test plans to typescript-tests/specs/
 *
 * After planning, the generator agent will use this seed as a template
 * for creating executable tests.
 */

import { test, expect } from '../fixtures/base.fixture';

test.describe('Seed Test for Planner', () => {
  test('seed - initialize app and verify ready state', async ({ page, envConfig }) => {
    // Navigate to the Opik frontend
    const frontendUrl = envConfig.getWebUrl();
    await page.goto(frontendUrl);

    // Wait for the application to be fully loaded and interactive
    await page.waitForLoadState('domcontentloaded');

    // Verify the app has loaded by checking for basic UI elements
    // This ensures the planner agent has a working environment
    await expect(page).toHaveTitle(/Opik/);

    // Log success for debugging
    console.log(`✓ Opik application ready at ${frontendUrl}`);
  });
});
