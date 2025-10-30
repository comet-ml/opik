import { test, expect } from '../../fixtures/tracing.fixture';
import type { Page } from '@playwright/test';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import type { TestHelperClient, Trace } from '../../helpers/test-helper-client';

test.describe('Traces CRUD Tests', () => {
  test.describe('Trace creation and visibility', () => {
    const verifyTraces = async (page: Page, projectName: string, helperClient: TestHelperClient, tracesNumber: number = 25, prefix: string = 'test-trace-') => {
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();

      const tracesUi = await tracesPage.getAllTraceNamesInProject();
      expect(tracesUi).toHaveLength(tracesNumber);

      const expectedNames = Array.from({ length: tracesNumber }, (_, i) => `${prefix}${i}`);
      expectedNames.forEach((name) => expect(tracesUi).toContain(name));

      const tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
      expect(tracesSdk).toHaveLength(tracesNumber);

      const traceSdkNames = tracesSdk.map((trace) => trace.name);
      expectedNames.forEach((name) => expect(traceSdkNames).toContain(name));
    };

    test('should verify traces created via decorator @sanity @regression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesDecorator,
    }) => {
      await verifyTraces(page, projectName, helperClient);
    });

    test('should verify traces created via client @regression @tracing', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      await verifyTraces(page, projectName, helperClient);
    });
  });

  test.describe('Trace deletion', () => {
    test('should delete traces via SDK', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      const tracesNumber = 10;

      await helperClient.createTracesClient(projectName, tracesNumber, 'test-trace-');
      await helperClient.waitForTracesVisible(projectName, tracesNumber, 30);

      let tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
      expect(tracesSdk).toHaveLength(tracesNumber);

      const tracesToDelete = tracesSdk.slice(0, 2);
      const traceIdsToDelete = tracesToDelete.map((trace: Trace) => trace.id);
      const traceNamesToDelete = tracesToDelete.map((trace: Trace) => trace.name);

      await helperClient.deleteTraces(traceIdsToDelete);
      await page.waitForTimeout(1000);
      await page.reload();
      await page.waitForTimeout(500);

      for (const traceName of traceNamesToDelete) {
        await expect(page.getByRole('row', { name: traceName })).not.toBeVisible();
      }

      tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
      const traceSdkNames = tracesSdk.map((trace: Trace) => trace.name);

      for (const traceName of traceNamesToDelete) {
        expect(traceSdkNames).not.toContain(traceName);
      }
    });

    test('should delete traces via UI', async ({
      page,
      helperClient,
      projectName,
      createTracesClient,
    }) => {
      const tracesNumber = 10;

      await helperClient.createTracesClient(projectName, tracesNumber, 'test-trace-');
      await helperClient.waitForTracesVisible(projectName, tracesNumber, 30);

      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.clickProject(projectName);

      let tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
      const traceNamesToDelete = tracesSdk.slice(0, 2).map((trace: Trace) => trace.name);

      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      await tracesPage.waitForTracesToBeVisible();

      for (const traceName of traceNamesToDelete) {
        await tracesPage.deleteSingleTraceByName(traceName);
      }

      await page.waitForTimeout(1000);
      await page.reload();
      await page.waitForTimeout(500);

      for (const traceName of traceNamesToDelete) {
        await expect(page.getByRole('row', { name: traceName })).not.toBeVisible();
      }

      tracesSdk = await helperClient.getTraces(projectName, tracesNumber);
      const traceSdkNames = tracesSdk.map((trace: Trace) => trace.name);

      for (const traceName of traceNamesToDelete) {
        expect(traceSdkNames).not.toContain(traceName);
      }
    });
  });
});
