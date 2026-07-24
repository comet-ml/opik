import { test } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';
import { ProjectsPage } from '../page-objects/projects.page';
import { LogsPage } from '../page-objects/logs.page';
import { DatasetsPage } from '../page-objects/datasets.page';
import { TestSuitesPage } from '../page-objects/test-suites.page';
import { ExperimentsPage } from '../page-objects/experiments.page';
import { DashboardsPage } from '../page-objects/dashboards.page';
import { PromptsPage } from '../page-objects/prompts.page';
import { AgentPlaygroundPage } from '../page-objects/agent-playground.page';
import { OptimizationsPage } from '../page-objects/optimizations.page';
import { AnnotationQueuesPage } from '../page-objects/annotation-queues.page';
import { OnlineEvaluationPage } from '../page-objects/online-evaluation.page';
import { AlertsPage } from '../page-objects/alerts.page';
import { screenshot } from './utils/screenshot';

test.setTimeout(300000);

test.describe('Visual Comparison - Empty States', () => {
  let projectId = '';
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = () => process.env.VISUAL_EMPTY_PROJECT_NAME!;

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

  test('E06: Dashboards default view', async ({ page }) => {
    const dashboardsPage = new DashboardsPage(page, baseUrl, workspace);
    await dashboardsPage.goto(projectId);
    await dashboardsPage.waitForLoaded();
    await screenshot(page, 'E06-dashboards-default');
  });

  test('E07: Prompt Library empty state', async ({ page }) => {
    const promptsPage = new PromptsPage(page, baseUrl, workspace);
    await promptsPage.goto(projectId);
    await promptsPage.waitForEmpty();
    await screenshot(page, 'E07-prompts-empty');
  });

  test('E08: Agent Playground empty state', async ({ page }) => {
    const agentPlaygroundPage = new AgentPlaygroundPage(page, baseUrl, workspace);
    await agentPlaygroundPage.goto(projectId);
    await agentPlaygroundPage.waitForEmpty();
    await screenshot(page, 'E08-agent-playground-empty');
  });

  test('E09: Optimization runs empty state', async ({ page }) => {
    const optimizationsPage = new OptimizationsPage(page, baseUrl, workspace);
    await optimizationsPage.goto(projectId);
    await optimizationsPage.waitForEmpty();
    await screenshot(page, 'E09-optimizations-empty');
  });

  test('E10: Annotation queues empty state', async ({ page }) => {
    const annotationQueuesPage = new AnnotationQueuesPage(page, baseUrl, workspace);
    await annotationQueuesPage.goto(projectId);
    await annotationQueuesPage.waitForEmpty();
    await screenshot(page, 'E10-annotation-queues-empty');
  });

  test('E11: Online evaluation empty state', async ({ page }) => {
    const onlineEvaluationPage = new OnlineEvaluationPage(page, baseUrl, workspace);
    await onlineEvaluationPage.goto(projectId);
    await onlineEvaluationPage.waitForEmpty();
    await screenshot(page, 'E11-online-evaluation-empty');
  });

  test('E12: Alerts empty state', async ({ page }) => {
    const alertsPage = new AlertsPage(page, baseUrl, workspace);
    await alertsPage.goto(projectId);
    await alertsPage.waitForEmpty();
    await screenshot(page, 'E12-alerts-empty');
  });
});
