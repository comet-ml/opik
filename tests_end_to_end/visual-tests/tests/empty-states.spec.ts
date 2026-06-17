import { test } from '@playwright/test';
import { getEnvironmentConfig } from '../../typescript-tests/config/env.config';
import { ProjectsPage } from '../page-objects/projects.page';
import { LogsPage } from '../page-objects/logs.page';
import { DatasetsPage } from '../page-objects/datasets.page';
import { TestSuitesPage } from '../page-objects/test-suites.page';
import { ExperimentsPage } from '../page-objects/experiments.page';
import { screenshot } from './utils/screenshot';

test.setTimeout(300000);

test.describe('Visual Comparison - Empty States', () => {
  let projectId = '';
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = () => process.env.VISUAL_PROJECT_NAME!;

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName());
    await projectsPage.waitForProject(projectName());
    projectId = await projectsPage.clickProjectAndGetId(projectName());
    await page.close();
  });

  test('E01: Logs - Traces empty state', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForEmptyTraces();
    await screenshot(page, 'E01-logs-traces-empty');
  });

  test('E02: Logs - Threads empty state', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.switchToThreads();
    await logsPage.waitForEmptyThreads();
    await screenshot(page, 'E02-logs-threads-empty');
  });

  test('E03: Datasets empty state', async ({ page }) => {
    const datasetsPage = new DatasetsPage(page, baseUrl, workspace);
    await datasetsPage.goto(projectId);
    await datasetsPage.waitForEmpty();
    await screenshot(page, 'E03-datasets-empty');
  });

  test('E04: Test Suites empty state', async ({ page }) => {
    const testSuitesPage = new TestSuitesPage(page, baseUrl, workspace);
    await testSuitesPage.goto(projectId);
    await testSuitesPage.waitForEmpty();
    await screenshot(page, 'E04-testsuites-empty');
  });

  test('E05: Experiments empty state', async ({ page }) => {
    const experimentsPage = new ExperimentsPage(page, baseUrl, workspace);
    await experimentsPage.goto(projectId);
    await experimentsPage.waitForEmpty();
    await screenshot(page, 'E05-experiments-empty');
  });
});
