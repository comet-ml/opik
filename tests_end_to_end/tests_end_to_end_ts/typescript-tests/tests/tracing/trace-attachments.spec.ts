import { test, expect } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';

test.describe('Trace Attachments Tests', () => {
  test('Trace attachments are correctly displayed for traces created via low-level client @fullregression @tracing @attachments', async ({
    page,
    projectName,
    createTraceWithAttachmentClient,
  }) => {
    const attachmentName = createTraceWithAttachmentClient;
    let traceNames: string[];

    await test.step('Navigate to project traces page', async () => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);
    });

    await test.step('Load traces and verify they exist', async () => {
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();
      traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);
    });

    await test.step('Verify attachment is present in each trace', async () => {
      const tracesPage = new TracesPage(page);
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await tracesPage.checkTraceAttachment(attachmentName);
      }
    });
  });

  test('Trace attachments are correctly displayed for traces created via decorator @sanity @happypaths @fullregression @tracing @attachments', async ({
    page,
    projectName,
    createTraceWithAttachmentDecorator,
  }) => {
    const attachmentName = createTraceWithAttachmentDecorator;
    let traceNames: string[];

    await test.step('Navigate to project traces page', async () => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);
    });

    await test.step('Load traces and verify they exist', async () => {
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();
      traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);
    });

    await test.step('Verify attachment is present in each trace', async () => {
      const tracesPage = new TracesPage(page);
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await tracesPage.checkTraceAttachment(attachmentName);
      }
    });
  });

  test('Span attachments are correctly displayed within traces @happypaths @fullregression @tracing @attachments', async ({
    page,
    projectName,
    createTraceWithSpanAttachment,
  }) => {
    const { attachmentName, spanName } = createTraceWithSpanAttachment;
    let traceNames: string[];

    await test.step('Navigate to project traces page', async () => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);
    });

    await test.step('Load traces and verify they exist', async () => {
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();
      traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);
    });

    await test.step('Verify span-level attachments are present (but no trace-level attachments)', async () => {
      const tracesPage = new TracesPage(page);
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await tracesPage.checkTraceAttachment(); // Verify no trace-level attachment

        const spansMenu = new TracesPageSpansMenu(page);
        await spansMenu.openSpanContent(spanName);
        await spansMenu.checkSpanAttachment(attachmentName);
      }
    });
  });
});
