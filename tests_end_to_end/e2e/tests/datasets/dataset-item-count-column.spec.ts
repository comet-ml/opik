import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import type { CountedDatasetRef } from '@e2e/fixtures';

/**
 * `dataset_items_count` is the number the Datasets grid renders as "Item
 * count", and nothing in the estate asserted it: `dataset-crud-smoke` only
 * checks a row is visible, `dataset-version-counters` asserts `items_total` off
 * the versions endpoint (a different field), and every `countItems()` counts
 * rendered rows on the items page. The column could report the wrong number for
 * every dataset in the workspace and the suite would stay green.
 *
 * The backend answers the field two different ways in one response: from the
 * latest version's `items_total` where the dataset has a version, and from an
 * O(N) scan of `dataset_items` for the rows that don't. Which rows get the scan
 * is decided per response page, so the seed deliberately mixes both kinds and
 * the page size is varied to re-compose that mix.
 *
 * See `counted-datasets.fixture.ts` for why the counts collide and why the
 * zero-item rows sit mid-list.
 */

const EXPECTED_DATASET_COUNT = 7;
/** The two datasets that never received an item, and so have no version to count from. */
const EXPECTED_ZERO_ITEM_DATASETS = 2;

/** id → item count, the shape every page of the list must agree on. */
function countsById(rows: Array<{ id: string; itemsCount: number }>): Record<string, number> {
  return Object.fromEntries(rows.map((row) => [row.id, row.itemsCount]));
}

function expectedCountsById(datasets: CountedDatasetRef[]): Record<string, number> {
  return Object.fromEntries(datasets.map((dataset) => [dataset.id, dataset.itemCount]));
}

test.describe('Datasets list — item count', { tag: ['@t2-cuj', '@area:datasets'] }, () => {
  /** Seven datasets seeded one at a time through the SDK bridge. */
  test.slow();

  /** Seven default-selected columns: give the grid room to render them all. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'Every dataset list row reports its real item total, on one page and split across pages',
    { tag: ['@cap:datasets.list-datasets'] },
    async ({ countedDatasets, backendClient }) => {
      const expected = expectedCountsById(countedDatasets.datasets);

      const rows = await test.step('The seed really mixes version-resolved rows with item-scan rows', async () => {
        const { rows, total } = await backendClient.listProjectDatasets({
          projectId: countedDatasets.projectId,
          type: 'dataset',
        });
        expect(total).toBe(EXPECTED_DATASET_COUNT);
        expect(rows).toHaveLength(EXPECTED_DATASET_COUNT);

        // A dataset that never received an item has no version, so its count is
        // the one the backend cannot take from `latest_version.items_total`.
        // Asserting the split here is what makes the rest of this spec mean
        // something: without both kinds present, the page never exercises the
        // narrowing at all and would pass on a build that got it wrong.
        const withoutVersion = rows.filter((row) => row.latestVersionName === null);
        const withVersion = rows.filter((row) => row.latestVersionName !== null);
        expect(withoutVersion).toHaveLength(EXPECTED_ZERO_ITEM_DATASETS);
        expect(withoutVersion.every((row) => row.itemsCount === 0)).toBe(true);
        expect(withVersion).toHaveLength(EXPECTED_DATASET_COUNT - EXPECTED_ZERO_ITEM_DATASETS);
        return rows;
      });

      await test.step('Each row\'s count equals the items that dataset actually stores', async () => {
        expect(countsById(rows)).toEqual(expected);

        for (const dataset of countedDatasets.datasets) {
          const storedIds = await backendClient.listDatasetItemIds(dataset.id);
          expect(storedIds).toHaveLength(dataset.itemCount);
        }
      });

      await test.step('Get-by-id and retrieve-by-name report the same count as the list', async () => {
        for (const dataset of countedDatasets.datasets) {
          expect(await backendClient.getDatasetItemsCount(dataset.id)).toBe(dataset.itemCount);
          expect(
            await backendClient.retrieveDatasetItemsCountByName({
              name: dataset.name,
              projectName: countedDatasets.projectName,
            }),
          ).toBe(dataset.itemCount);
        }
      });

      await test.step('Splitting the same datasets across pages does not move any count', async () => {
        // The set of rows needing the fallback scan is derived from the rows in
        // the response page, so a smaller page is a different composition of
        // version-resolved and scanned rows — not a repeat of the read above.
        for (const size of [3, 1]) {
          const collected: Record<string, number> = {};
          const pageCount = Math.ceil(EXPECTED_DATASET_COUNT / size);
          for (let page = 1; page <= pageCount; page++) {
            const result = await backendClient.listProjectDatasets({
              projectId: countedDatasets.projectId,
              type: 'dataset',
              page,
              size,
            });
            expect(result.total).toBe(EXPECTED_DATASET_COUNT);
            Object.assign(collected, countsById(result.rows));
          }
          expect(collected).toEqual(expected);
        }
      });
    },
  );

  test(
    'The rendered "Item count" column shows each dataset\'s real item total',
    { tag: ['@cap:datasets.list-datasets'] },
    async ({ countedDatasets, page }) => {
      const datasets = new DatasetsPage(page);

      await test.step('Open the Datasets list for the seeded project', async () => {
        await datasets.goto(countedDatasets.projectId);
        await datasets.waitForReady();
        await expect(datasets.columnHeader('Item count')).toBeVisible();
        await expect(datasets.datasetRows).toHaveCount(EXPECTED_DATASET_COUNT);
      });

      await test.step('Every row renders its own count', async () => {
        for (const dataset of countedDatasets.datasets) {
          const cell = datasets.itemCountCell(dataset.id);
          // Exactly one cell must resolve — an ambiguous match should fail here
          // rather than silently assert against whichever came first.
          await expect(cell).toHaveCount(1);
          await expect(cell).toHaveText(String(dataset.itemCount));
        }
      });
    },
  );
});
