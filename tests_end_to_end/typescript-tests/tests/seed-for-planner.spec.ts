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
 * IMPORTANT: This file must contain a single top-level test('seed', ...) call.
 * Do NOT wrap in test.describe() — the playwright-test MCP server requires
 * a bare test named 'seed' to bootstrap the planner/generator agents.
 */

import { test, expect } from '../fixtures/base.fixture';

test('seed', async ({ page, envConfig }) => {
  const frontendUrl = envConfig.getWebUrl();
  const workspace = envConfig.getConfig().workspace;

  await page.goto(frontendUrl);
  await page.waitForLoadState('domcontentloaded');

  await expect(page).toHaveTitle(/Opik/);
  console.log(`Opik application ready at ${frontendUrl} (workspace: ${workspace})`);

  await page.goto(`${frontendUrl}/${workspace}/projects`);
  await expect(page.getByText('Projects').first()).toBeVisible({ timeout: 10000 });
  console.log('Projects section accessible');

  await page.goto(`${frontendUrl}/${workspace}/datasets`);
  await expect(page.getByText('Datasets').first()).toBeVisible({ timeout: 10000 });
  console.log('Datasets section accessible');

  await page.goto(`${frontendUrl}/${workspace}/experiments`);
  await expect(page.getByText('Experiments').first()).toBeVisible({ timeout: 10000 });
  console.log('Experiments section accessible');

  await page.goto(`${frontendUrl}/${workspace}/prompts`);
  await expect(page.getByText('Prompts').first()).toBeVisible({ timeout: 10000 });
  console.log('Prompts section accessible');

  await page.goto(`${frontendUrl}/${workspace}/playground`);
  await expect(page.getByText('Playground').first()).toBeVisible({ timeout: 10000 });
  console.log('Playground section accessible');

  await page.goto(`${frontendUrl}/${workspace}/configuration?tab=feedback-definitions`);
  await expect(page.getByText('Feedback definitions').first()).toBeVisible({ timeout: 10000 });
  console.log('Configuration section accessible');

  console.log('All Opik sections verified and accessible');
});
