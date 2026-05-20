import { test as base } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { ATTACHMENTS } from './trace-attachments-shared';

base.describe('Trace Attachments - Decorator', () => {
  for (const attachment of ATTACHMENTS) {
    base(`Trace attachment ${attachment.name} is correctly displayed via decorator @fullregression @tracing @attachments`, async ({
      page,
      projectName,
      helperClient,
    }) => {
      let attachmentName: string;
      let traceNames: string[];

      await base.step('Create trace with attachment via decorator', async () => {
        attachmentName = await helperClient.createTraceWithAttachmentDecorator(projectName, attachment.path);
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

      await base.step('Verify attachment is present in each trace', async () => {
        const tracesPage = new TracesPage(page);
        for (const traceName of traceNames) {
          await tracesPage.clickFirstTraceWithName(traceName);
          await tracesPage.checkTraceAttachment(attachmentName);
        }
      });
    });
  }

  base(`Trace attachments are correctly displayed for traces created via decorator (sanity) @sanity @happypaths @fullregression @tracing @attachments`, async ({
    page,
    projectName,
    helperClient,
  }) => {
    base.info().annotations.push({
      type: 'description',
      description: `Tests that trace-level attachments are correctly displayed for traces created via the @track decorator.

Steps:
1. Create a project (handled by fixture)
2. Create a trace with an attachment using the @track decorator
3. Navigate to the project traces page
4. Load and verify traces exist
5. For each trace, open the trace details and verify the attachment is present

This test ensures trace-level attachments created via decorator are properly stored and displayed in the UI.`
    });

    const firstAttachment = ATTACHMENTS[0];
    let attachmentName: string;
    let traceNames: string[];

    await base.step('Create trace with attachment via decorator', async () => {
      attachmentName = await helperClient.createTraceWithAttachmentDecorator(projectName, firstAttachment.path);
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

    await base.step('Verify attachment is present in each trace', async () => {
      const tracesPage = new TracesPage(page);
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await tracesPage.checkTraceAttachment(attachmentName);
      }
    });
  });
});
