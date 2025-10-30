import { test, expect } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';

test.describe('Trace Spans Tests', () => {
  test.describe('Span creation and verification', () => {
    test('should verify spans in traces created via low-level client', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansClient;

      // Navigate to project traces
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      // Get all traces
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      const traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);

      // Verify spans for each trace
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        const spansMenu = new TracesPageSpansMenu(page);

        // Verify each expected span
        for (let count = 0; count < spanConfig.count; count++) {
          const spanName = `${spanConfig.prefix}${count}`;
          await spansMenu.checkSpanExistsByName(spanName);
        }

        await page.keyboard.press('Escape');
      }
    });

    test('should verify spans in traces created via decorator', async ({
      page,
      projectName,
      createTracesWithSpansDecorator,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansDecorator;

      // Navigate to project traces
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      // Get all traces
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      const traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);

      // Verify spans for each trace
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        const spansMenu = new TracesPageSpansMenu(page);

        // Verify each expected span
        for (let count = 0; count < spanConfig.count; count++) {
          const spanName = `${spanConfig.prefix}${count}`;
          await spansMenu.checkSpanExistsByName(spanName);
        }

        await page.keyboard.press('Escape');
      }
    });
  });

  test.describe('Span details and metadata', () => {
    test('should verify span details in traces created via low-level client', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansClient;

      // Navigate to project traces
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      // Get all traces
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      const traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);

      // Verify details for each trace
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await page.waitForTimeout(500);
        const spansMenu = new TracesPageSpansMenu(page);

        // Check each span's details
        for (let count = 0; count < spanConfig.count; count++) {
          const spanName = `${spanConfig.prefix}${count}`;

          // Select span
          await spansMenu.getFirstSpanByName(spanName).click();

          // Verify feedback scores
          await spansMenu.getFeedbackScoresTab().click();
          await page.waitForTimeout(250);

          if (spanConfig.feedback_scores) {
            for (const score of spanConfig.feedback_scores) {
              await expect(page.getByRole('cell', { name: score.name, exact: true })).toBeVisible();
              await expect(
                page.getByRole('cell', { name: String(score.value), exact: true })
              ).toBeVisible();
            }
          }

          // Verify metadata
          await spansMenu.getMetadataTab().click();
          if (spanConfig.metadata) {
            for (const [key, value] of Object.entries(spanConfig.metadata)) {
              await expect(page.getByText(`${key}: ${value}`)).toBeVisible();
            }
          }

          await page.waitForTimeout(500);
        }

        await page.keyboard.press('Escape');
      }
    });

    test('should verify span details in traces created via decorator', async ({
      page,
      projectName,
      createTracesWithSpansDecorator,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansDecorator;

      // Navigate to project traces
      const projectsPage = new ProjectsPage(page);
      await projectsPage.goto();
      await projectsPage.checkProjectExistsWithRetry(projectName, 5000);
      await projectsPage.clickProject(projectName);

      // Get all traces
      const tracesPage = new TracesPage(page);
      await tracesPage.initialize();
      const traceNames = await tracesPage.getAllTraceNamesOnPage();
      expect(traceNames.length).toBeGreaterThan(0);

      // Verify details for each trace
      for (const traceName of traceNames) {
        await tracesPage.clickFirstTraceWithName(traceName);
        await page.waitForTimeout(500);
        const spansMenu = new TracesPageSpansMenu(page);

        // Check each span's details
        for (let count = 0; count < spanConfig.count; count++) {
          const spanName = `${spanConfig.prefix}${count}`;

          // Select span
          await spansMenu.getFirstSpanByName(spanName).click();

          // Verify feedback scores
          await spansMenu.getFeedbackScoresTab().click();
          await page.waitForTimeout(250);

          if (spanConfig.feedback_scores) {
            for (const score of spanConfig.feedback_scores) {
              await expect(page.getByRole('cell', { name: score.name, exact: true })).toBeVisible();
              await expect(
                page.getByRole('cell', { name: String(score.value), exact: true })
              ).toBeVisible();
            }
          }

          // Verify metadata
          await spansMenu.getMetadataTab().click();
          if (spanConfig.metadata) {
            for (const [key, value] of Object.entries(spanConfig.metadata)) {
              await expect(page.getByText(`${key}: ${value}`)).toBeVisible();
            }
          }

          await page.waitForTimeout(500);
        }

        await page.keyboard.press('Escape');
      }
    });
  });
});
