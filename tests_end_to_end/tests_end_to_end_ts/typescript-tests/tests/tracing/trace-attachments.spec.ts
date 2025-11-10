import { test as base } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';

// Define all attachment files to test (same as Python conftest.py)
const ATTACHMENTS = [
  { path: 'test_files/attachments/audio01.wav', name: 'audio01.wav' },
  { path: 'test_files/attachments/audio02.mp3', name: 'audio02.mp3' },
  { path: 'test_files/attachments/json01.json', name: 'json01.json' },
  { path: 'test_files/attachments/pdf01.pdf', name: 'pdf01.pdf' },
  { path: 'test_files/attachments/test-image1.jpg', name: 'test-image1.jpg' },
  { path: 'test_files/attachments/test-image2.png', name: 'test-image2.png' },
  { path: 'test_files/attachments/test-image3.gif', name: 'test-image3.gif' },
  { path: 'test_files/attachments/test-image4.svg', name: 'test-image4.svg' },
  { path: 'test_files/attachments/text01.txt', name: 'text01.txt' },
  { path: 'test_files/attachments/video01.webm', name: 'video01.webm' },
];

base.describe('Trace Attachments Tests', () => {
  // Tests for attachments via low-level client
  base.describe('Low-level client attachments', () => {
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

  // Tests for attachments via decorator
  base.describe('Decorator attachments', () => {
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

    // Keep one sanity test with decorator
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

  // Tests for span-level attachments
  base.describe('Span-level attachments', () => {
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

    // Keep one sanity test with span attachment
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
});
