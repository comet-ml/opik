import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * The Datasets list's "Item count" column is a backend-computed number, and the
 * backend computes it two different ways in the same response: from the latest
 * dataset version's `items_total` where there is one, and from a
 * `count(DISTINCT id)` scan over `dataset_items` where there is not
 * (`DatasetService.versionItemsTotal`). OPIK-8175 narrowed that scan to only
 * the datasets that still need it, so the interesting page is one that mixes
 * both kinds — a row answered from the wrong branch, or dropped from the
 * narrowed id set entirely, is a wrong number on the default landing page that
 * nobody re-derives by hand.
 *
 * Nothing in the estate reads that column: `dataset-crud-smoke.spec.ts` asserts
 * a dataset's *row* is listed, and `dataset-version-counters.spec.ts` asserts
 * the Version history tab's own Item count (`items_total`), which is a
 * different column on a different page fed straight from the version row.
 *
 * Driven on both surfaces, which is the point of the spec: the number is
 * asserted against ground truth through the API, and then read off the rendered
 * cell, so a backend that computes it correctly and a page that renders a stale
 * or mis-keyed one are distinguishable failures.
 *
 * Shape of the seed, chosen so one list response covers both branches:
 *  - EMPTY_SIZE is 0 and the dataset is never inserted into, so it has no
 *    version at all and can only be answered by the legacy fallback;
 *  - the other two are seeded through `Dataset.insert()`, which cuts a version,
 *    so they are answered from `items_total`;
 *  - MULTI_SIZE is above the SDK's 1000-item batch size, so its count also has
 *    to survive an insert that reached the backend as several batches.
 */
const EMPTY_SIZE = 0;
const SMALL_SIZE = 7;
const MULTI_SIZE = 2500;

/** Items removed from the small dataset once every count has been asserted. */
const DELETED_FROM_SMALL = 3;

function seedItems(count: number, label: string) {
  return Array.from({ length: count }, (_, index) => ({
    input: `${label} input ${index}`,
    expected_output: `${label} output ${index}`,
    // Dataset.insert() drops duplicate entries by content hash, so every item
    // has to differ in something. The index is what guarantees `count` items
    // are actually stored rather than silently deduplicated to fewer.
    seq: index,
  }));
}

test.describe('Dataset item count', { tag: ['@area:datasets'] }, () => {
  /** The Item count column is off-screen at the default 1280px viewport. */
  test.use({ viewport: { width: 1600, height: 900 } });

  /**
   * A 2500-item insert against a cloud backend outruns the default budget.
   * Measured at ~11s against staging, so this is headroom rather than need.
   */
  test.slow();

  test(
    'The list reports each dataset\'s item count from the branch that applies to it, and the page renders those numbers',
    { tag: ['@t2-cuj', '@cap:datasets.list-datasets'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace, page }) => {
      const emptyName = `${testNamespace}-empty`;
      const smallName = `${testNamespace}-small`;
      const multiName = `${testNamespace}-multi`;

      const datasetIds = await test.step(
        `Seed one dataset with no items, one with ${SMALL_SIZE} and one with ${MULTI_SIZE}`,
        async () => {
          const ids: Record<string, string> = {};
          for (const [name, size] of [
            [emptyName, EMPTY_SIZE],
            [smallName, SMALL_SIZE],
            [multiName, MULTI_SIZE],
          ] as const) {
            const created = await sdkClient.python.createDataset({
              project_name: project.name,
              name,
              description: `item count, ${size} items`,
            });
            registerDatasetCleanup(created.id, name);
            ids[name] = created.id;
            if (size > 0) {
              await sdkClient.python.insertDatasetItems({
                project_name: project.name,
                dataset_name: name,
                items: seedItems(size, name),
              });
            }
          }
          return ids;
        },
      );

      await test.step('One list response covers both count branches at once', async () => {
        const { rows, total } = await backendClient.listDatasetSummaries({
          projectId: project.id,
        });
        // The narrowing this spec is about happens per response: the fallback
        // scan is issued for the subset of rows on the page that need it. If
        // the three datasets came back over several requests, none of that is
        // being exercised.
        expect(total, 'the project holds exactly the three seeded datasets').toBe(3);
        expect(rows.map((r) => r.name).sort()).toEqual(
          [emptyName, smallName, multiName].sort(),
        );

        const byName = new Map(rows.map((r) => [r.name, r]));
        // Which branch each row took. The empty dataset was never inserted
        // into, so it has no version to read items_total from and is the only
        // row the fallback scan can answer; the other two have one.
        expect(byName.get(emptyName)!.latestVersionName, 'no version => fallback branch').toBeNull();
        expect(byName.get(smallName)!.latestVersionName).toBe('v1');
        expect(byName.get(multiName)!.latestVersionName).toBe('v1');

        expect(byName.get(emptyName)!.datasetItemsCount).toBe(EMPTY_SIZE);
        expect(byName.get(smallName)!.datasetItemsCount).toBe(SMALL_SIZE);
        expect(byName.get(multiName)!.datasetItemsCount).toBe(MULTI_SIZE);
      });

      await test.step('Each count agrees with the items the dataset actually holds', async () => {
        // The seed sizes above are what was asked for; this is what was stored.
        // Asserting the column against the items endpoint is what makes the
        // check independent of the enrichment that produced it.
        for (const [name, expected] of [
          [emptyName, EMPTY_SIZE],
          [smallName, SMALL_SIZE],
          [multiName, MULTI_SIZE],
        ] as const) {
          const itemIds = await backendClient.listDatasetItemIds(datasetIds[name]);
          expect(itemIds, `${name}: no item stored twice`).toHaveLength(
            new Set(itemIds).size,
          );
          expect(itemIds, `${name}: items endpoint agrees with the list count`).toHaveLength(
            expected,
          );
        }
      });

      const datasets = await test.step('The Datasets page renders the same three numbers', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto(project.id);
        await datasetsPage.waitForReady();
        // Rendered raw, not thousands-separated — this column has no
        // accessorFn, unlike the Version history tab's Item count.
        await expect(datasetsPage.datasetItemCount(emptyName)).toHaveText(String(EMPTY_SIZE));
        await expect(datasetsPage.datasetItemCount(smallName)).toHaveText(String(SMALL_SIZE));
        await expect(datasetsPage.datasetItemCount(multiName)).toHaveText(String(MULTI_SIZE));
        return datasetsPage;
      });

      const survivingSmallIds = await test.step(
        `Delete ${DELETED_FROM_SMALL} items from the ${SMALL_SIZE}-item dataset`,
        async () => {
          const itemIds = await backendClient.listDatasetItemIds(datasetIds[smallName]);
          await backendClient.deleteDatasetItemsByIds(itemIds.slice(0, DELETED_FROM_SMALL));
          return itemIds.slice(DELETED_FROM_SMALL);
        },
      );

      await test.step('The count follows the delete on both surfaces', async () => {
        const remaining = SMALL_SIZE - DELETED_FROM_SMALL;

        const itemIds = await backendClient.listDatasetItemIds(datasetIds[smallName]);
        expect(new Set(itemIds), 'exactly the items that were not deleted survive').toEqual(
          new Set(survivingSmallIds),
        );

        const { rows } = await backendClient.listDatasetSummaries({ projectId: project.id });
        const byName = new Map(rows.map((r) => [r.name, r]));
        expect(byName.get(smallName)!.datasetItemsCount).toBe(remaining);
        // The delete committed a new version, so this row is still answered
        // from items_total — and the two datasets it did not touch must be
        // unmoved, which a count computed over the wrong id set would not be.
        expect(byName.get(smallName)!.latestVersionName).toBe('v2');
        expect(byName.get(emptyName)!.datasetItemsCount).toBe(EMPTY_SIZE);
        expect(byName.get(multiName)!.datasetItemsCount).toBe(MULTI_SIZE);

        await page.reload();
        await datasets.waitForReady();
        await expect(datasets.datasetItemCount(smallName)).toHaveText(String(remaining));
        await expect(datasets.datasetItemCount(multiName)).toHaveText(String(MULTI_SIZE));
        await expect(datasets.datasetItemCount(emptyName)).toHaveText(String(EMPTY_SIZE));
      });
    },
  );
});
