import { test, expect } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';

test.describe('Trace Attachments Tests', () => {
  test('should verify attachment in trace created via low-level client @regression @tracing @attachments', async ({
    page,
    projectName,
    createTraceWithAttachmentClient,
  }) => {
    const attachmentName = createTraceWithAttachmentClient;

    // Navigate to project traces
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
    await projectsPage.clickProject(projectName);

    // Wait for traces to appear in UI
    const tracesPage = new TracesPage(page);
    await tracesPage.initialize();
    await tracesPage.waitForTracesToBeVisible();

    // Get all traces
    const traceNames = await tracesPage.getAllTraceNamesOnPage();
    expect(traceNames.length).toBeGreaterThan(0);

    // Verify attachment for trace
    for (const traceName of traceNames) {
      await tracesPage.clickFirstTraceWithName(traceName);
      await tracesPage.checkTraceAttachment(attachmentName);
    }
  });

  test('should verify attachment in trace created via decorator @regression @tracing @attachments', async ({
    page,
    projectName,
    createTraceWithAttachmentDecorator,
  }) => {
    const attachmentName = createTraceWithAttachmentDecorator;

    // Navigate to project traces
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
    await projectsPage.clickProject(projectName);

    // Wait for traces to appear in UI
    const tracesPage = new TracesPage(page);
    await tracesPage.initialize();
    await tracesPage.waitForTracesToBeVisible();

    // Get all traces
    const traceNames = await tracesPage.getAllTraceNamesOnPage();
    expect(traceNames.length).toBeGreaterThan(0);

    // Verify attachment for trace
    for (const traceName of traceNames) {
      await tracesPage.clickFirstTraceWithName(traceName);
      await tracesPage.checkTraceAttachment(attachmentName);
    }
  });

  test('should verify attachment in span within trace', async ({
    page,
    projectName,
    createTraceWithSpanAttachment,
  }) => {
    const { attachmentName, spanName } = createTraceWithSpanAttachment;

    // Navigate to project traces
    const projectsPage = new ProjectsPage(page);
    await projectsPage.goto();
    await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
    await projectsPage.clickProject(projectName);

    // Wait for traces to appear in UI
    const tracesPage = new TracesPage(page);
    await tracesPage.initialize();
    await tracesPage.waitForTracesToBeVisible();

    // Get all traces
    const traceNames = await tracesPage.getAllTraceNamesOnPage();
    expect(traceNames.length).toBeGreaterThan(0);

    // Verify attachment for span
    for (const traceName of traceNames) {
      await tracesPage.clickFirstTraceWithName(traceName);
      await tracesPage.checkTraceAttachment(); // Verify no trace-level attachment

      const spansMenu = new TracesPageSpansMenu(page);
      await spansMenu.openSpanContent(spanName);
      await spansMenu.checkSpanAttachment(attachmentName);
    }
  });
});
