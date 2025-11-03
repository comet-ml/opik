import { test, expect } from '../../fixtures/tracing.fixture';
import { ProjectsPage } from '../../page-objects/projects.page';
import { TracesPage } from '../../page-objects/traces.page';
import { TracesPageSpansMenu } from '../../page-objects/traces-spans-menu.page';

test.describe('Trace Spans Tests', () => {
  test.describe('Span creation and verification', () => {
    test('Spans created via low-level client API are correctly displayed within their parent traces @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansClient;
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
        traceNames = await tracesPage.getAllTraceNamesOnPage();
        expect(traceNames.length).toBeGreaterThan(0);
      });

      await test.step(`Verify each trace contains ${spanConfig.count} expected spans`, async () => {
        const tracesPage = new TracesPage(page);
        for (const traceName of traceNames) {
          await tracesPage.clickFirstTraceWithName(traceName);
          const spansMenu = new TracesPageSpansMenu(page);

          for (let count = 0; count < spanConfig.count; count++) {
            const spanName = `${spanConfig.prefix}${count}`;
            await spansMenu.checkSpanExistsByName(spanName);
          }

          await page.keyboard.press('Escape');
        }
      });
    });

    test('Spans created via @track decorator are correctly displayed within their parent traces @sanity @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansDecorator,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansDecorator;
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
        traceNames = await tracesPage.getAllTraceNamesOnPage();
        expect(traceNames.length).toBeGreaterThan(0);
      });

      await test.step(`Verify each trace contains ${spanConfig.count} expected spans`, async () => {
        const tracesPage = new TracesPage(page);
        for (const traceName of traceNames) {
          await tracesPage.clickFirstTraceWithName(traceName);
          const spansMenu = new TracesPageSpansMenu(page);

          for (let count = 0; count < spanConfig.count; count++) {
            const spanName = `${spanConfig.prefix}${count}`;
            await spansMenu.checkSpanExistsByName(spanName);
          }

          await page.keyboard.press('Escape');
        }
      });
    });
  });

  test.describe('Span details and metadata', () => {
    test('Span details (feedback scores and metadata) are correctly displayed for traces created via low-level client @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansClient,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansClient;
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
        traceNames = await tracesPage.getAllTraceNamesOnPage();
        expect(traceNames.length).toBeGreaterThan(0);
      });

      await test.step('Verify feedback scores and metadata for all spans in each trace', async () => {
        const tracesPage = new TracesPage(page);

        for (const traceName of traceNames) {
          await tracesPage.clickFirstTraceWithName(traceName);
          await page.waitForTimeout(500);
          const spansMenu = new TracesPageSpansMenu(page);

          for (let count = 0; count < spanConfig.count; count++) {
            const spanName = `${spanConfig.prefix}${count}`;

            await spansMenu.getFirstSpanByName(spanName).click();

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

    test('Span details (feedback scores and metadata) are correctly displayed for traces created via decorator @sanity @happypaths @fullregression @tracing', async ({
      page,
      projectName,
      createTracesWithSpansDecorator,
    }) => {
      const { traceConfig, spanConfig } = createTracesWithSpansDecorator;
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
        traceNames = await tracesPage.getAllTraceNamesOnPage();
        expect(traceNames.length).toBeGreaterThan(0);
      });

      await test.step('Verify feedback scores and metadata for all spans in each trace', async () => {
        const tracesPage = new TracesPage(page);

        for (const traceName of traceNames) {
          await tracesPage.clickFirstTraceWithName(traceName);
          await page.waitForTimeout(500);
          const spansMenu = new TracesPageSpansMenu(page);

          for (let count = 0; count < spanConfig.count; count++) {
            const spanName = `${spanConfig.prefix}${count}`;

            await spansMenu.getFirstSpanByName(spanName).click();

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
});
