import { test, expect, Page } from '@playwright/test';
import { getEnvironmentConfig } from '../../typescript-tests/config/env.config';
import { ProjectsPage } from '../page-objects/projects.page';
import { LogsPage } from '../page-objects/logs.page';
import { DatasetsPage } from '../page-objects/datasets.page';
import { TestSuitesPage } from '../page-objects/test-suites.page';
import { ExperimentsPage } from '../page-objects/experiments.page';
import * as path from 'path';
import * as fs from 'fs';

const IS_COMPARISON_RUN = process.env.SKIP_TEARDOWN !== '1';
const COMPARISON_DIR = path.join(__dirname, '../screenshots/comparison');

function dynamicMasks(page: Page) {
  return [
    page.locator('time'),
    page.locator('[data-testid="timestamp"]'),
    page.locator('[data-testid="date"]'),
    page.locator('td').filter({ hasText: /\d+ (second|minute|hour|day)s? ago/ }),
    page.locator('td').filter({ hasText: /\d{4}-\d{2}-\d{2}/ }),
    page.locator('td').filter({ hasText: /\d+ mins? ago/ }),
  ];
}

const screenshotOpts = (page: Page) => ({
  mask: dynamicMasks(page),
  animations: 'disabled' as const,
});

async function screenshot(page: Page, name: string) {
  if (IS_COMPARISON_RUN) {
    fs.mkdirSync(COMPARISON_DIR, { recursive: true });
    await page.screenshot({
      path: path.join(COMPARISON_DIR, `${name}.png`),
      mask: dynamicMasks(page),
      animations: 'disabled',
    });
  }
  await expect(page).toHaveScreenshot(`${name}.png`, screenshotOpts(page));
}

test.setTimeout(300000);

test('Visual Comparison - Opik UI', async ({ page }) => {
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = process.env.VISUAL_PROJECT_NAME!;
  const experimentName = process.env.VISUAL_EXPERIMENT_NAME!;
  let projectId = '';

  await test.step('Screenshot: Projects page', async () => {
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName);
    await projectsPage.waitForProject(projectName);
    await screenshot(page, '01-projects-page');
  });

  await test.step('Screenshot: Logs page - Traces view', async () => {
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName);
    await projectsPage.waitForProject(projectName);
    projectId = await projectsPage.clickProjectAndGetId(projectName);

    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.waitForTracesReady();
    await screenshot(page, '02-logs-traces');
  });

  await test.step('Screenshot: Logs page - Threads view', async () => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.switchToThreads();
    await logsPage.waitForThreadsReady();
    await screenshot(page, '03-logs-threads');
  });

  await test.step('Screenshot: Datasets page', async () => {
    const datasetsPage = new DatasetsPage(page, baseUrl, workspace);
    await datasetsPage.goto(projectId);
    await datasetsPage.waitForReady();
    await screenshot(page, '04-datasets-page');
  });

  await test.step('Screenshot: Test Suites page', async () => {
    const testSuitesPage = new TestSuitesPage(page, baseUrl, workspace);
    await testSuitesPage.goto(projectId);
    await testSuitesPage.waitForReady();
    await screenshot(page, '05-testsuites-page');
  });

  await test.step('Screenshot: Experiments page', async () => {
    const experimentsPage = new ExperimentsPage(page, baseUrl, workspace);
    await experimentsPage.goto(projectId);
    await experimentsPage.waitForExperiment(experimentName);
    await screenshot(page, '06-experiments-page');
  });
});
