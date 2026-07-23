import { test } from '@playwright/test';
import { getEnvironmentConfig } from '../config/env.config';
import { TestHelperClient } from '../helpers/test-helper-client';
import { ProjectsPage } from '../page-objects/projects.page';
import { LogsPage } from '../page-objects/logs.page';
import { TraceDetailsPanelPage } from '../page-objects/trace-details-panel.page';
import { screenshot } from './utils/screenshot';

const TRACE_NAME = 'visual-sidebar-trace';
const PROMPT_NAME = 'visual-sidebar-prompt';
const ATTACHMENT_PATH = 'visual-tests/fixtures/sidebar-attachment.png';
// The traces table doesn't show a Name column by default, so rows are located by their input text instead.
const TRACE_INPUT_TEXT = 'What is Opik?';

test.setTimeout(300000);

test.describe('Visual Comparison - Trace Sidebar', () => {
  let projectId = '';
  const { baseUrl, workspace } = getEnvironmentConfig().getConfig();
  const projectName = () => process.env.VISUAL_SIDEBAR_PROJECT_NAME!;

  test.beforeAll(async ({ browser }) => {
    const client = new TestHelperClient();
    await client.createRichTraceForSidebar(projectName(), TRACE_NAME, PROMPT_NAME, ATTACHMENT_PATH);

    const page = await browser.newPage();
    const projectsPage = new ProjectsPage(page, baseUrl, workspace);
    await projectsPage.goto();
    await projectsPage.searchAndWait(projectName());
    await projectsPage.waitForProject(projectName());
    projectId = await projectsPage.clickProjectAndGetId(projectName());
    await page.close();
  });

  test('S01: Trace sidebar - Messages tab', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady(TRACE_INPUT_TEXT);
    await logsPage.openTrace(TRACE_INPUT_TEXT);

    const panel = new TraceDetailsPanelPage(page);
    await panel.waitForLoaded();
    await panel.selectTab('Messages');
    await panel.root.getByText('What is Opik?').waitFor({ state: 'visible' });
    await screenshot(page, 'S01-trace-sidebar-messages', [panel.statsRowMask]);
  });

  test('S02: Trace sidebar - Details tab', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady(TRACE_INPUT_TEXT);
    await logsPage.openTrace(TRACE_INPUT_TEXT);

    const panel = new TraceDetailsPanelPage(page);
    await panel.waitForLoaded();
    await panel.selectTab('Details');
    await panel.root.getByText('Attachments').waitFor({ state: 'visible' });
    await panel.root.getByText('sidebar-attachment.png').waitFor({ state: 'visible' });
    await screenshot(page, 'S02-trace-sidebar-details', [panel.statsRowMask]);
  });

  test('S03: Trace sidebar - Feedback scores tab', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady(TRACE_INPUT_TEXT);
    await logsPage.openTrace(TRACE_INPUT_TEXT);

    const panel = new TraceDetailsPanelPage(page);
    await panel.waitForLoaded();
    await panel.selectTab('Feedback scores');
    await panel.root.getByText('correctness').waitFor({ state: 'visible' });
    await screenshot(page, 'S03-trace-sidebar-feedback-scores', [panel.statsRowMask]);
  });

  test('S04: Trace sidebar - Prompts tab', async ({ page }) => {
    const logsPage = new LogsPage(page, baseUrl, workspace);
    await logsPage.goto(projectId);
    await logsPage.waitForTracesReady(TRACE_INPUT_TEXT);
    await logsPage.openTrace(TRACE_INPUT_TEXT);

    const panel = new TraceDetailsPanelPage(page);
    await panel.waitForLoaded();
    await panel.selectTab('Prompts');
    await panel.root.getByText(PROMPT_NAME).waitFor({ state: 'visible' });
    await screenshot(page, 'S04-trace-sidebar-prompts', [panel.statsRowMask]);
  });
});
