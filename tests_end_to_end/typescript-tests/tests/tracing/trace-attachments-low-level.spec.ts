import { test as base } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { ATTACHMENTS } from './trace-attachments-shared';

base.describe('Trace Attachments - Low-level client', () => {
  for (const attachment of ATTACHMENTS) {
    base(`Trace attachment ${attachment.name} is correctly displayed via low-level client @fullregression @tracing @attachments`, async ({
      page,
      projectName,
      helperClient,
    }) => {
      let attachmentName: string;
      let traceNames: string[];

      await base.step('Create trace with attachment via low-level client', async () => {
        attachmentName = await helperClient.createTraceWithAttachmentClient(projectName, attachment.path);
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
});
