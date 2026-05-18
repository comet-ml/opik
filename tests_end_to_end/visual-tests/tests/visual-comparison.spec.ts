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
    // mask absolute date cells — format "D MMM YYYY, h:mm A" used by TimeCell (e.g., "15 May 2026, 3:26 PM")
    page.locator('td').filter({ hasText: /\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \d{4},/ }),
    // mask UUID columns
    page.locator('td').filter({ hasText: /[0-9a-f]{8}-[0-9a-f]{4}/ }),
    // mask breadcrumb — shows dynamic project/dataset names on sub-pages
    page.locator('nav[aria-label="breadcrumb"]'),
    // mask "Back to <project name>" button — project name is dynamic
    page.locator('button').filter({ hasText: /^Back to / }),
    // mask pagination "Showing X-Y of Z" — counts differ between environments
    page.locator('span').filter({ hasText: /^Showing \d/ }),
    // mask duration cells (e.g. "1.23s") — timing varies between environments
    page.locator('td').filter({ hasText: /^\d+\.?\d*s$/ }),
  ];
}

const screenshotOpts = (page: Page) => ({
  mask: dynamicMasks(page),
  animations: 'disabled' as const,
});

async function hideDemoBanner(page: Page) {
  await page.addStyleTag({
    content: '.z-10.h-8.bg-primary { display: none !important; }',
  });
}

async function screenshot(page: Page, name: string) {
  await hideDemoBanner(page);
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

test.describe('Visual Comparison - Opik UI', () => {
  let projectId = '';
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = () => process.env.VISUAL_PROJECT_NAME!;
  const experimentName = () => process.env.VISUAL_EXPERIMENT_NAME!;

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName());
    await projectsPage.waitForProject(projectName());
    projectId = await projectsPage.clickProjectAndGetId(projectName());
    await page.close();
  });

  test('01: Projects page', async ({ page }) => {
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName());
    await projectsPage.waitForProject(projectName());
    await screenshot(page, '01-projects-page');
  });

  test('02: Logs - Traces view', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady('input-0');
    await screenshot(page, '02-logs-traces');
  });

  test('03: Logs - Threads view', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.switchToThreads();
    await logsPage.waitForThreadsReady('Hello, what is Opik?');
    await screenshot(page, '03-logs-threads');
  });

  test('04: Datasets page', async ({ page }) => {
    const datasetsPage = new DatasetsPage(page, baseUrl, workspace);
    await datasetsPage.goto(projectId);
    await datasetsPage.waitForReady('visual-dataset');
    await screenshot(page, '04-datasets-page');
  });

  test('05: Test Suites page', async ({ page }) => {
    const testSuitesPage = new TestSuitesPage(page, baseUrl, workspace);
    await testSuitesPage.goto(projectId);
    await testSuitesPage.waitForReady('visual-testsuite');
    await screenshot(page, '05-testsuites-page');
  });

  test('06: Experiments page', async ({ page }) => {
    const experimentsPage = new ExperimentsPage(page, baseUrl, workspace);
    await experimentsPage.goto(projectId);
    await experimentsPage.waitForExperiment(experimentName());
    await screenshot(page, '06-experiments-page');
  });
});
