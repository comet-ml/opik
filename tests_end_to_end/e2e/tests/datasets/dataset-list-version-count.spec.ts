import { test, expect } from '@e2e/fixtures';
import { DatasetsPage, DATASET_COLUMN } from '@e2e/pom/datasets.page';

/**
 * The Datasets list's "Item count" follows the latest committed version
 * (OPIK-8176).
 *
 * With versioning on, the number the list renders is the latest version's
 * `items_total`, and `latest_version` now arrives from a MySQL transaction
 * opened separately from the dataset row itself. A version lookup that comes
 * back empty under the new subscription falls back to the legacy
 * `dataset_items` count with no error anywhere — the page keeps rendering a
 * number, just the wrong one, and only a dataset whose two counts disagree can
 * tell the difference.
 *
 * That is what the second insert builds: it rewrites one existing item and adds
 * two new ones, so the version reports 5 items after 3, with added and modified
 * as different numbers. A fallback to a stale count, or to counting only what
 * the last batch carried, produces 3 or 2 rather than 5.
 *
 * dataset-version-counters.spec.ts already asserts the counters a version
 * *stores*, on the Version history tab. This asserts the different thing:
 * that the list page reads the latest version's total, which is the number a
 * user sees without opening the dataset at all.
 */

const SEEDED_ITEMS = 3;
const ADDED_ITEMS = 2;
const MODIFIED_ITEMS = 1;
const TOTAL_AFTER_COMMIT = SEEDED_ITEMS + ADDED_ITEMS;

test.describe(
  'Datasets list — Item count follows the committed version',
  { tag: ['@t2-cuj', '@area:datasets'] },
  () => {
    /** Two version commits against a remote backend, plus the settle between them. */
    test.slow();

    test(
      'committing a second version moves the list\'s Item count to that version\'s items_total',
      { tag: ['@cap:datasets.list-datasets'] },
      async ({ dataset, project, sdkClient, backendClient, page }) => {
        const onlyRow = async () => {
          const { total, rows } = await backendClient.listEnrichedDatasets({
            projectId: project.id,
          });
          // The project is seeded with exactly this one dataset, so the whole
          // answer is assertable — a find() over a longer list would pass even
          // if the read had leaked rows.
          expect(total, 'the project holds exactly the seeded dataset').toBe(1);
          expect(rows, 'one row for one dataset').toHaveLength(1);
          expect(rows[0].id, 'the row is the seeded dataset').toBe(dataset.id);
          return rows[0];
        };

        await test.step('The freshly seeded dataset reports its first version', async () => {
          await expect
            .poll(
              async () => {
                const row = await onlyRow();
                return {
                  datasetItemsCount: row.datasetItemsCount,
                  latestVersionName: row.latestVersionName,
                };
              },
              { message: 'the list settles on v1 with the seeded item count', timeout: 60_000 },
            )
            .toEqual({ datasetItemsCount: SEEDED_ITEMS, latestVersionName: 'v1' });

          const versions = await backendClient.getDatasetVersions(dataset.id);
          expect(versions, 'one insert cut exactly one version').toHaveLength(1);
          expect(versions[0].versionName).toBe('v1');
          expect(versions[0].itemsTotal, 'v1 stores the seeded total').toBe(SEEDED_ITEMS);
        });

        const rewrittenItemId = await test.step('Commit a second version: one rewrite and two new items', async () => {
          const existing = await backendClient.getDatasetItems(dataset.id);
          expect(existing, 'the seed is intact before the second insert').toHaveLength(SEEDED_ITEMS);
          const rewritten = existing[0].id;

          // One insert() call, so one new version covering both the
          // modification and the additions. Re-sending an existing id is what
          // makes it a modification rather than a fourth row.
          await sdkClient.python.insertDatasetItems({
            project_name: project.name,
            dataset_name: dataset.name,
            items: [
              { id: rewritten, input: 'seed input 1 rewritten', expected_output: 'seed output 1 rewritten' },
              { input: 'added input 1', expected_output: 'added output 1' },
              { input: 'added input 2', expected_output: 'added output 2' },
            ] as unknown as Array<Record<string, unknown>>,
          });
          return rewritten;
        });

        await test.step('The list moves to v2 and to that version\'s items_total', async () => {
          await expect
            .poll(
              async () => {
                const row = await onlyRow();
                return {
                  datasetItemsCount: row.datasetItemsCount,
                  latestVersionName: row.latestVersionName,
                };
              },
              { message: 'the list settles on v2 with the new total', timeout: 60_000 },
            )
            .toEqual({ datasetItemsCount: TOTAL_AFTER_COMMIT, latestVersionName: 'v2' });
        });

        await test.step('v2 stores that total, and the dataset really holds that many items', async () => {
          const versions = await backendClient.getDatasetVersions(dataset.id);
          expect(versions, 'two inserts cut two versions').toHaveLength(2);

          const latest = versions.find((v) => v.isLatest);
          expect(latest, 'one version is marked latest').toBeDefined();
          expect(latest!.versionName).toBe('v2');
          expect(latest!.itemsTotal, 'v2 totals the rewrite and the additions').toBe(
            TOTAL_AFTER_COMMIT,
          );
          expect(latest!.itemsAdded, 'only the two new ids count as added').toBe(ADDED_ITEMS);
          expect(latest!.itemsModified, 'the re-sent id counts as modified').toBe(MODIFIED_ITEMS);

          // The number on the page is only worth anything if the dataset
          // actually holds that many rows — a version total that outruns the
          // stored items is exactly the disagreement this catches.
          const itemIds = await backendClient.listDatasetItemIds(dataset.id);
          expect(new Set(itemIds).size, 'the dataset holds the total v2 reports').toBe(
            TOTAL_AFTER_COMMIT,
          );
          expect(itemIds, 'the rewritten item was updated in place, not duplicated').toContain(
            rewrittenItemId,
          );
        });

        await test.step('The Datasets page renders the new total', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();

          const itemCount = datasets.cell(dataset.id, DATASET_COLUMN.itemCount);
          await expect(itemCount).toHaveCount(1);
          await expect(itemCount, 'Item count on the list page').toHaveText(
            String(TOTAL_AFTER_COMMIT),
          );
        });
      },
    );
  },
);
