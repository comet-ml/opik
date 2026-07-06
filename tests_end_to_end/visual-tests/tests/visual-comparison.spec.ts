import { test } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';
import { TestHelperClient } from '../helpers/test-helper-client';
import { ProjectsPage } from '../page-objects/projects.page';
import { LogsPage } from '../page-objects/logs.page';
import { DatasetsPage } from '../page-objects/datasets.page';
import { TestSuitesPage } from '../page-objects/test-suites.page';
import { ExperimentsPage } from '../page-objects/experiments.page';
import { screenshot } from './utils/screenshot';
import * as path from 'path';
import * as fs from 'fs';

const DATASET_NAME = 'visual-dataset';
const TEST_SUITE_NAME = 'visual-testsuite';
const EXPERIMENT_NAME = 'visual-experiment';
const TEST_SUITE_EXP_NAME = 'visual-testsuite-exp';
const STATE_FILE = path.join(__dirname, '../.test-state.json');

test.setTimeout(300000);

test.describe('Visual Comparison - Opik UI', () => {
  let projectId = '';
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = () => process.env.VISUAL_PROJECT_NAME!;
  const experimentName = () => EXPERIMENT_NAME;

  test.beforeAll(async ({ browser }) => {
    const client = new TestHelperClient();

    await client.createTracesWithSpansClient(
      projectName(),
      { count: 3, prefix: 'visual-trace-', tags: ['visual'], metadata: { env: 'visual-test' } },
      { count: 2, prefix: 'visual-span-', tags: ['visual'], metadata: { step: '1' } },
    );

    await client.createThreadsClient(projectName(), [
      { thread_id: 'visual-thread-1', inputs: ['Hello, what is Opik?'], outputs: ['Opik is an LLM observability platform.'] },
      { thread_id: 'visual-thread-2', inputs: ['How do traces work?'], outputs: ['Traces capture the full execution of an LLM call.'] },
    ]);

    await client.createDatasetForProject(DATASET_NAME, projectName());
    await client.insertDatasetItems(DATASET_NAME, [
      { input: 'What is 2 + 2?', output: '4' },
      { input: 'What is the capital of France?', output: 'Paris' },
      { input: 'Name a primary color.', output: 'Red' },
    ]);
    await client.waitForDatasetItemsCount(DATASET_NAME, 3, 15);

    await client.createTestSuiteDatasetForProject(TEST_SUITE_NAME, projectName());
    await client.insertDatasetItems(TEST_SUITE_NAME, [
      { input: 'Test input A', output: 'Test output A' },
      { input: 'Test input B', output: 'Test output B' },
    ]);
    await client.waitForDatasetItemsCount(TEST_SUITE_NAME, 2, 15);

    const experiment = await client.createExperimentForProject(EXPERIMENT_NAME, DATASET_NAME, projectName());
    fs.writeFileSync(STATE_FILE, JSON.stringify({ experimentId: experiment.id }, null, 2));

    const testSuiteExperiment = await client.createTestSuiteExperimentForProject(TEST_SUITE_EXP_NAME, TEST_SUITE_NAME, projectName());
    fs.writeFileSync(STATE_FILE, JSON.stringify({ experimentId: experiment.id, testSuiteExperimentId: testSuiteExperiment.id }, null, 2));

    const page = await browser.newPage();
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName());
    await projectsPage.waitForProject(projectName());
    projectId = await projectsPage.clickProjectAndGetId(projectName());
    await page.close();
  });

  test('01: Projects page', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady('input-0');
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
