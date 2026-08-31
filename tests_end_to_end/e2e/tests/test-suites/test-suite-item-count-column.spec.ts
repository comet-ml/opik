import { test, expect } from '@e2e/fixtures';
import { TestSuitesPage } from '@e2e/pom/test-suites.page';

/**
 * The Test suites grid renders the same enriched `dataset_items_count` as the
 * Datasets grid — suites and datasets share a table, and the backend enriches
 * both through the same call — but it is a separate page with its own column
 * set, reached through its own type-filtered read. An assertion on the Datasets
 * list does not cover it, and nothing else in the estate asserts a suite's item
 * count at all.
 *
 * Four suites, one of them empty: the empty one is the only row here whose
 * count cannot come from a dataset version, so the page exercises both the
 * version-resolved and the item-scan answer in one response.
 */

const EXPECTED_SUITE_COUNT = 4;

test.describe('Test suites list — item count', { tag: ['@t2-cuj', '@area:test-suites'] }, () => {
  /** Six default-selected columns: give the grid room to render them all. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'Every test suite list row reports its real item total, in the API and in the rendered column',
    { tag: ['@cap:test-suites.list-suites'] },
    async ({ countedTestSuites, backendClient, page }) => {
      const expected = Object.fromEntries(
        countedTestSuites.datasets.map((suite) => [suite.id, suite.itemCount]),
      );

      await test.step('The type-filtered list returns every suite with its real item total', async () => {
        const { rows, total } = await backendClient.listProjectDatasets({
          projectId: countedTestSuites.projectId,
          type: 'evaluation_suite',
        });
        // Assert the whole answer, not just that the seeded suites are in it: a
        // read that also returned the project's plain datasets would be a leak,
        // and finding four correct rows inside it would still pass.
        expect(total).toBe(EXPECTED_SUITE_COUNT);
        expect(rows).toHaveLength(EXPECTED_SUITE_COUNT);
        expect(Object.fromEntries(rows.map((row) => [row.id, row.itemsCount]))).toEqual(expected);

        // The empty suite is the row with no version, so it is the one whose
        // count comes from the item scan rather than from `items_total`.
        const withoutVersion = rows.filter((row) => row.latestVersionName === null);
        expect(withoutVersion).toHaveLength(1);
        expect(withoutVersion[0].itemsCount).toBe(0);
      });

      await test.step('Each suite really stores the items its row claims', async () => {
        for (const suite of countedTestSuites.datasets) {
          const storedIds = await backendClient.listDatasetItemIds(suite.id);
          expect(storedIds).toHaveLength(suite.itemCount);
        }
      });

      const suites = new TestSuitesPage(page);

      await test.step('Open the Test suites list for the seeded project', async () => {
        await suites.goto(countedTestSuites.projectId);
        await suites.waitForReady();
        await expect(suites.columnHeader('Item count')).toBeVisible();
        await expect(suites.testSuiteRows).toHaveCount(EXPECTED_SUITE_COUNT);
      });

      await test.step('Every row renders its own count', async () => {
        for (const suite of countedTestSuites.datasets) {
          const cell = suites.itemCountCell(suite.id);
          await expect(cell).toHaveCount(1);
          await expect(cell).toHaveText(String(suite.itemCount));
        }
      });
    },
  );
});
