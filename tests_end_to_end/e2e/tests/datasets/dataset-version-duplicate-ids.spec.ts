import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import { uuid7 } from '@e2e/core/backend';

/**
 * A dataset version sizes itself from the batch it was cut on, and renders that
 * size as the "Item count" column of the Version history tab (and as the
 * datasets list's item count). One batch may legitimately carry the SAME
 * dataset_item_id more than once — two rows with different content collapse to
 * one stored item — so the version has to count DISTINCT ids, not rows.
 *
 * The existing counter coverage seeds only unique ids, so nothing catches the
 * failure this covers: a version reporting more items than the dataset holds.
 * That is silent wrongness — the rows are right, the number above them is not,
 * and a user has no way to tell.
 *
 * Both version-creating paths are exercised, because they size the version
 * independently:
 *  - `createFirstVersion`, when the batch lands on a dataset with no version;
 *  - `applyDelta`, when it lands on a dataset that already has one.
 */

/** Rows in the seed batch, and how many distinct ids they carry. */
const FIRST_BATCH_ROWS = 5;
const FIRST_BATCH_DISTINCT = 4;
const DELTA_BATCH_ROWS = 3;
const DELTA_BATCH_DISTINCT = 2;

/**
 * A batch that carries `distinct` ids across `rows` rows: the last id is
 * repeated to make up the difference, each repeat with different content.
 *
 * The differing content matters — the Python SDK dedupes items whose payloads
 * hash identically before it ever sends them, so identical repeats would never
 * reach the backend and the batch would prove nothing.
 */
function batchWithRepeatedId(
  ids: string[],
  rows: number,
  revision: string,
): Array<Record<string, unknown>> {
  const repeated = ids[ids.length - 1];
  const items = ids.map((id, index) => ({
    id,
    input: `${revision} input ${index}`,
    expected_output: `${revision} output ${index}`,
  }));
  for (let repeat = 1; repeat <= rows - ids.length; repeat++) {
    items.push({
      id: repeated,
      input: `${revision} input repeat ${repeat}`,
      expected_output: `${revision} output repeat ${repeat}`,
    });
  }
  return items;
}

test.describe('Dataset version counters — repeated item ids', { tag: ['@area:datasets'] }, () => {
  /** Items toolbar collapses to icon-only (no accessible name) below ~850px container width. */
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'A first batch that repeats an item id sizes the version from distinct ids, and the Version history tab renders that size',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, testNamespace, page }) => {
      const datasetName = `${testNamespace}-first`;
      const ids = Array.from({ length: FIRST_BATCH_DISTINCT }, () => uuid7());
      const repeatedId = ids[ids.length - 1];

      const datasetId = await test.step(
        `Seed a fresh dataset with one ${FIRST_BATCH_ROWS}-row batch carrying ${FIRST_BATCH_DISTINCT} distinct ids`,
        async () => {
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'first version sized from a batch with a repeated id',
            items: batchWithRepeatedId(ids, FIRST_BATCH_ROWS, 'v1'),
          });
          return created.id;
        },
      );

      try {
        await test.step('The dataset stores one row per distinct id', async () => {
          const storedIds = await backendClient.listDatasetItemIds(datasetId);
          // Asserted as a set AND a length: a stored duplicate would keep the
          // set right while the length — and the counter below — go wrong.
          expect(storedIds).toHaveLength(FIRST_BATCH_DISTINCT);
          expect(new Set(storedIds)).toEqual(new Set(ids));
          expect(storedIds.filter((id) => id === repeatedId)).toHaveLength(1);
        });

        await test.step('The first version counts the repeated id once', async () => {
          const versions = await backendClient.getDatasetVersions(datasetId);
          // One insert() call, so exactly one version — a second version here
          // would mean the batch was split, and the totals below would be
          // measuring something other than what the test set up.
          expect(versions).toHaveLength(1);
          expect(versions[0]).toMatchObject({
            versionName: 'v1',
            itemsTotal: FIRST_BATCH_DISTINCT,
            itemsAdded: FIRST_BATCH_DISTINCT,
            itemsModified: 0,
            isLatest: true,
          });
        });

        await test.step('The Version history tab renders that total as "Item count"', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const items = await datasets.openDatasetByName(datasetName);
          await items.waitForReady();

          // The rendered rows are the other half of the claim: the count the
          // tab shows has to be the count the Records tab actually lists.
          expect(await items.countItems()).toBe(FIRST_BATCH_DISTINCT);
          await expect(items.itemRowById(repeatedId)).toHaveCount(1);

          await items.openVersionHistory();
          await expect(items.versionHistoryRow('v1')).toHaveCount(1);
          await expect(items.versionItemCount('v1')).toHaveText(
            FIRST_BATCH_DISTINCT.toLocaleString('en-US'),
          );
        });
      } finally {
        await backendClient.deleteDataset(datasetId);
      }
    },
  );

  test(
    'A delta batch that repeats a new item id adds it once to the running version total',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, testNamespace }) => {
      const datasetName = `${testNamespace}-delta`;
      const baseIds = Array.from({ length: 2 }, () => uuid7());
      const deltaIds = Array.from({ length: DELTA_BATCH_DISTINCT }, () => uuid7());
      const expectedTotal = baseIds.length + DELTA_BATCH_DISTINCT;

      const datasetId = await test.step('Seed a dataset that already has a version', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'delta version sized from a batch with a repeated id',
          items: baseIds.map((id, index) => ({
            id,
            input: `base input ${index}`,
            expected_output: `base output ${index}`,
          })),
        });
        return created.id;
      });

      try {
        await test.step('The base version is the plain, all-unique case', async () => {
          // The delta assertions below are arithmetic on this number, so a
          // wrong base would make them pass for the wrong reason.
          const versions = await backendClient.getDatasetVersions(datasetId);
          expect(versions).toHaveLength(1);
          expect(versions[0]).toMatchObject({
            versionName: 'v1',
            itemsTotal: baseIds.length,
            isLatest: true,
          });
        });

        await test.step(
          `Insert a ${DELTA_BATCH_ROWS}-row batch carrying ${DELTA_BATCH_DISTINCT} distinct new ids`,
          async () => {
            await sdkClient.python.insertDatasetItems({
              project_name: project.name,
              dataset_name: datasetName,
              items: batchWithRepeatedId(deltaIds, DELTA_BATCH_ROWS, 'v2'),
            });
          },
        );

        await test.step('The delta version counts the repeated id once, in both total and added', async () => {
          const versions = await backendClient.getDatasetVersions(datasetId);
          expect(versions).toHaveLength(2);
          const latest = versions.filter((v) => v.isLatest);
          expect(latest).toHaveLength(1);
          expect(latest[0]).toMatchObject({
            versionName: 'v2',
            itemsTotal: expectedTotal,
            itemsAdded: DELTA_BATCH_DISTINCT,
            // Every id in the delta batch is new, so nothing was modified —
            // the repeated row must not be booked as an edit of itself.
            itemsModified: 0,
          });
        });

        await test.step('And that total is the number of items the dataset actually holds', async () => {
          const storedIds = await backendClient.listDatasetItemIds(datasetId);
          expect(storedIds).toHaveLength(expectedTotal);
          expect(new Set(storedIds)).toEqual(new Set([...baseIds, ...deltaIds]));
        });
      } finally {
        await backendClient.deleteDataset(datasetId);
      }
    },
  );
});
