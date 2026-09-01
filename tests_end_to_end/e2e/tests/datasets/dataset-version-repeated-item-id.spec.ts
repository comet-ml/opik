import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';
import { uuid7 } from '@e2e/core/backend';

/**
 * A dataset version's `items_total` counts DISTINCT item ids, not the number of
 * batch entries that produced them. A single `PUT /v1/private/datasets/items`
 * batch may carry the same item id twice — the endpoint upserts, so the second
 * entry replaces the first and the version holds one row, not two.
 *
 * `dataset-version-counters.spec.ts` already asserts `items_total` against the
 * ids actually stored, but every id in its seed is a fresh UUIDv7 and its two
 * ranges are disjoint, so entries and distinct ids are the same number
 * throughout: it passes identically whether or not the count de-duplicates.
 * This spec makes the two numbers differ on purpose (51 entries, 50 ids), which
 * is the only shape where the distinction is observable — and "Item count" on
 * the Version history tab is a number a user cannot sanity-check by eye.
 *
 * Seeded through `backendClient.writeDatasetItemsBatch` rather than the SDK
 * bridge: `Dataset.insert()` drops duplicate entries by content hash before
 * batching, so the repeated id would never reach the endpoint.
 */
const DISTINCT_IDS = 50;
/** The repeated entry: `ids[0]` sent twice in one batch, so 51 entries for 50 ids. */
const FIRST_BATCH_ENTRIES = DISTINCT_IDS + 1;
const SECOND_BATCH_ADDITIONS = 10;

/**
 * The repeat carries DIFFERENT content from the first entry on purpose. An
 * identical repeat would still collapse to one row, but it would do so even if
 * the backend de-duplicated on content rather than on id — and it would leave
 * the "last write wins" half of the upsert unasserted.
 */
function item(id: string, revision: string): { id: string; data: Record<string, unknown> } {
  return { id, data: { input: `input ${revision}`, expected_output: `output ${revision}` } };
}

test.describe('Dataset version counters — repeated item ids', { tag: ['@area:datasets'] }, () => {
  test(
    'A batch that repeats an item id counts one row for it, and the Version history tab renders that count',
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerDatasetCleanup,
      testNamespace,
      page,
    }) => {
      const datasetName = `${testNamespace}-repeat-id`;
      const ids = Array.from({ length: DISTINCT_IDS + SECOND_BATCH_ADDITIONS }, () => uuid7());

      const datasetId = await test.step('Create an empty dataset', async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'version counters, repeated item id',
        });
        // Registered the moment the id exists: the dataset is created mid-test,
        // so no seed fixture can know it upfront, and datasets do not cascade
        // with their project.
        registerDatasetCleanup(created.id, datasetName);
        return created.id;
      });

      await test.step(
        `Write one grouped batch of ${FIRST_BATCH_ENTRIES} entries covering ${DISTINCT_IDS} ids`,
        async () => {
          const entries = [
            ...ids.slice(0, DISTINCT_IDS).map((id) => item(id, 'v1')),
            // The 51st entry: ids[0] again, with content the first entry did not have.
            item(ids[0], 'v1-superseded'),
          ];
          expect(entries, 'the seed must send one more entry than it has ids').toHaveLength(
            FIRST_BATCH_ENTRIES,
          );
          await backendClient.writeDatasetItemsBatch({
            datasetId,
            batchGroupId: crypto.randomUUID(),
            items: entries,
          });
        },
      );

      await test.step(
        `The version counts ${DISTINCT_IDS} items, not ${FIRST_BATCH_ENTRIES}`,
        async () => {
          const versions = await backendClient.getDatasetVersions(datasetId);
          expect(versions, 'one grouped batch cuts exactly one version').toHaveLength(1);
          expect(versions[0].itemsTotal, 'items_total counts distinct ids').toBe(DISTINCT_IDS);
          expect(versions[0].itemsAdded, 'the repeat is not a second addition').toBe(
            DISTINCT_IDS,
          );
          expect(
            versions[0].itemsModified,
            'a repeat inside one batch is an upsert, not a modification of a stored row',
          ).toBe(0);
          expect(versions[0].isLatest).toBe(true);
        },
      );

      await test.step('And that count agrees with the rows actually stored', async () => {
        // The counter is only worth rendering if it matches reality. Asserting
        // the id list too rules out the other way to reach 50 — storing 51 rows
        // and reporting 50.
        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        expect(itemIds, 'the dataset holds one row per distinct id').toHaveLength(DISTINCT_IDS);
        expect(new Set(itemIds).size, 'and no id is stored twice').toBe(DISTINCT_IDS);
      });

      await test.step(
        `Write a second batch: ids[0] again plus ${SECOND_BATCH_ADDITIONS} fresh ids`,
        async () => {
          await backendClient.writeDatasetItemsBatch({
            datasetId,
            batchGroupId: crypto.randomUUID(),
            items: [
              item(ids[0], 'v2-edited'),
              ...ids.slice(DISTINCT_IDS).map((id) => item(id, 'v2')),
            ],
          });
        },
      );

      await test.step(
        'The second version splits its entries into 10 additions and 1 modification',
        async () => {
          // Three different numbers — 10 added, 1 modified, 60 total — so the
          // assertion cannot pass by coincidence the way a single count could.
          const versions = await backendClient.getDatasetVersions(datasetId);
          expect(versions).toHaveLength(2);
          const latest = versions.filter((v) => v.isLatest);
          expect(latest, 'exactly one version is the latest').toHaveLength(1);
          expect(latest[0].itemsAdded, 'the fresh ids').toBe(SECOND_BATCH_ADDITIONS);
          expect(latest[0].itemsModified, 'ids[0], already stored, is a modification').toBe(1);
          expect(latest[0].itemsTotal, 'carried forward plus added').toBe(
            DISTINCT_IDS + SECOND_BATCH_ADDITIONS,
          );

          const itemIds = await backendClient.listDatasetItemIds(datasetId);
          expect(itemIds).toHaveLength(DISTINCT_IDS + SECOND_BATCH_ADDITIONS);
          expect(new Set(itemIds).size).toBe(latest[0].itemsTotal);
        },
      );

      await test.step('The Version history tab renders both counts as "Item count"', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(datasetName);
        await items.waitForReady();
        await items.openVersionHistory();

        await expect(items.versionItemCount('v1')).toHaveText(String(DISTINCT_IDS));
        await expect(items.versionItemCount('v2')).toHaveText(
          String(DISTINCT_IDS + SECOND_BATCH_ADDITIONS),
        );
      });
    },
  );
});
