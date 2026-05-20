import { test as base } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';
import { ATTACHMENTS } from './trace-attachments-shared';

base.describe('Trace Attachments - Span-level', () => {
  for (const attachment of ATTACHMENTS) {
    base(`Span attachment ${attachment.name} is correctly displayed within traces @fullregression @tracing @attachments`, async ({
      page,
      projectName,
      helperClient,
    }) => {
      let attachmentName: string;
      let spanName: string;
      let traceNames: string[];

      await base.step('Create trace with span attachment', async () => {
        const result = await helperClient.createTraceWithSpanAttachment(projectName, attachment.path);
        attachmentName = result.attachmentName;
        spanName = result.spanName;
      });

      await base.step('Navigate to project traces page', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);
      });

      await base.step('Load traces and verify they exist', async () => {
        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();
        traceNames = await tracesPage.getAllTraceNamesOnPage();
        base.expect(traceNames.length).toBeGreaterThan(0);
      });

      await base.step('Verify span-level attachments are present (but no trace-level attachments)', async () => {
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
  }

  base(`Span attachments are correctly displayed within traces (sanity) @happypaths @fullregression @tracing @attachments`, async ({
    page,
    projectName,
    helperClient,
  }) => {
    base.info().annotations.push({
      type: 'description',
      description: `Tests that span-level attachments are correctly displayed within traces (without trace-level attachments).

Steps:
1. Create a project (handled by fixture)
2. Create a trace with a span-level attachment
3. Navigate to the project traces page
4. Load and verify traces exist
5. For each trace:
   - Verify no trace-level attachment is present
   - Open the span details
   - Verify the span-level attachment is present

This test ensures span-level attachments are properly stored, associated with the correct span, and displayed in the UI.`
    });

    const firstAttachment = ATTACHMENTS[0];
    let attachmentName: string;
    let spanName: string;
    let traceNames: string[];

    await base.step('Create trace with span attachment', async () => {
      const result = await helperClient.createTraceWithSpanAttachment(projectName, firstAttachment.path);
      attachmentName = result.attachmentName;
      spanName = result.spanName;
    });

    await base.step('Navigate to project traces page', async () => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);
    });

    await base.step('Load traces and verify they exist', async () => {
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();
      traceNames = await tracesPage.getAllTraceNamesOnPage();
      base.expect(traceNames.length).toBeGreaterThan(0);
    });

    await base.step('Verify span-level attachments are present (but no trace-level attachments)', async () => {
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
