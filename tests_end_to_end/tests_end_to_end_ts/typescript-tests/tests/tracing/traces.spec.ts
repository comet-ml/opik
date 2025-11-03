import { test, expect } from '../../fixtures/tracing.fixture';
import type { Page } from '@playwright/test';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import type { TestHelperClient, Trace } from '../../helpers/test-helper-client';

test.describe('Traces CRUD Tests', () => {
  test.describe('Trace creation and visibility', () => {
    const verifyTraces = async (page: Page, projectName: string, helperClient: TestHelperClient, tracesNumber: number = 25, prefix: string = 'test-trace-') => {
      await test.step('Navigate to project and open traces view', async () => {
        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
        await projectsPage.clickProject(projectName);
      });

      await test.step('Initialize traces page and wait for traces to load', async () => {
        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();
      });

      await test.step(`Verify ${tracesNumber} traces are visible in UI`, async () => {
        const tracesPage = new TracesPage(page);
        const tracesUi = await tracesPage.getAllTraceNamesInProject();
        expect(tracesUi).toHaveLength(tracesNumber);

        const expectedNames = Array.from({ length: tracesNumber }, (_, i) => `${prefix}${i}`);
        expectedNames.forEach((name) => expect(tracesUi).toContain(name));
      });

      await test.step(`Verify ${tracesNumber} traces exist via SDK`, async () => {
        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        expect(tracesSdk).toHaveLength(tracesNumber);

        const expectedNames = Array.from({ length: tracesNumber }, (_, i) => `${prefix}${i}`);
        const traceSdkNames = tracesSdk.map((trace) => trace.name);
        expectedNames.forEach((name) => expect(traceSdkNames).toContain(name));
      });
    };

    test('Traces created via @track decorator are visible in UI and retrievable via SDK @sanity @happypaths @fullregression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesDecorator,
    }) => {
      await verifyTraces(page, projectName, helperClient);
    });

    test('Traces created via low-level client API are visible in UI and retrievable via SDK @happypaths @fullregression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      await verifyTraces(page, projectName, helperClient);
    });
  });

  test.describe('Trace deletion', () => {
    test('Traces can be deleted via SDK and changes reflect in UI @fullregression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      const tracesNumber = 10;
      let traceIdsToDelete: string[];
      let traceNamesToDelete: string[];

      await test.step('Create and verify test traces', async () => {
        await helperClient.createTracesClient(projectName, tracesNumber, 'test-trace-');
        await helperClient.waitForTracesVisible(projectName, tracesNumber, 30);

        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        expect(tracesSdk).toHaveLength(tracesNumber);
      });

      await test.step('Select traces to delete via SDK', async () => {
        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        const tracesToDelete = tracesSdk.slice(0, 2);
        traceIdsToDelete = tracesToDelete.map((trace: Trace) => trace.id);
        traceNamesToDelete = tracesToDelete.map((trace: Trace) => trace.name);
      });

      await test.step('Delete traces using SDK', async () => {
        await helperClient.deleteTraces(traceIdsToDelete);
        await page.waitForTimeout(1000);
      });

      await test.step('Verify deleted traces are not visible in UI', async () => {
        await page.reload();
        await page.waitForTimeout(500);

        for (const traceName of traceNamesToDelete) {
          await expect(page.getByRole('row', { name: traceName })).not.toBeVisible();
        }
      });

      await test.step('Verify deleted traces are not retrievable via SDK', async () => {
        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        const traceSdkNames = tracesSdk.map((trace: Trace) => trace.name);

        for (const traceName of traceNamesToDelete) {
          expect(traceSdkNames).not.toContain(traceName);
        }
      });
    });

    test('Traces can be deleted via UI and changes reflect in SDK @happypaths @fullregression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      const tracesNumber = 10;
      let traceNamesToDelete: string[];

      await test.step('Create test traces and navigate to project', async () => {
        await helperClient.createTracesClient(projectName, tracesNumber, 'test-trace-');
        await helperClient.waitForTracesVisible(projectName, tracesNumber, 30);

        const projectsPage = new ProjectsPage(page);
        await projectsPage.goto();
        await projectsPage.clickProject(projectName);
      });

      await test.step('Select and delete traces using UI', async () => {
        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        traceNamesToDelete = tracesSdk.slice(0, 2).map((trace: Trace) => trace.name);

        const tracesPage = new TracesPage(page);
        await tracesPage.initialize();
        await tracesPage.waitForTracesToBeVisible();

        for (const traceName of traceNamesToDelete) {
          await tracesPage.deleteSingleTraceByName(traceName);
        }

        await page.waitForTimeout(1000);
      });

      await test.step('Verify deleted traces are not visible in UI after reload', async () => {
        await page.reload();
        await page.waitForTimeout(500);

        for (const traceName of traceNamesToDelete) {
          await expect(page.getByRole('row', { name: traceName })).not.toBeVisible();
        }
      });

      await test.step('Verify deleted traces are not retrievable via SDK', async () => {
        const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
        const traceSdkNames = tracesSdk.map((trace: Trace) => trace.name);

        for (const traceName of traceNamesToDelete) {
          expect(traceSdkNames).not.toContain(traceName);
        }
      });
    });
  });
});
