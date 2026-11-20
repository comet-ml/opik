import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * `Dataset.insert()` consumes its items lazily and hashes each one as it is
 * yielded — before the batch carrying it is sent. So when the source raises
 * part-way, the object's local dedup cache holds items that never reached the
 * backend, and a retry on that same object would skip every one of them as a
 * duplicate: the dataset stays empty and nothing reports an error. opik#9393
 * fixes it by marking the cache out of sync whenever the funnel does not
 * complete, forcing the retry to re-sync against what the backend really holds.
 *
 * `dataset-insert-deduplication.spec.ts` is the nearest existing spec and does
 * not cover this: its re-sync is triggered by a `deduplication=False` insert,
 * a different (and pre-existing) trigger, and it would pass unchanged with this
 * failure path broken.
 *
 * `surface: both`. The SDK half is the write and the read-back of what landed;
 * the UI half is the Version history tab, where the recovered insert has to
 * read as one version of four items. That matters because the counters are the
 * only place a user could notice a short dataset — the Records tab shows rows
 * without saying how many were meant to be there.
 */

/**
 * Four items, each with distinct content so every one of them is individually
 * findable in the read-back.
 *
 * Four and not four hundred because the SDK batches at 1,000: a count below
 * that means the aborted insert's one and only batch is never flushed, so
 * nothing it hashed reaches the backend. That is the state the fix has to
 * recover from, and a seed large enough to flush a batch mid-way would be
 * testing partial-upload recovery instead.
 */
const ITEMS = [
  { input: 'retry probe 1', expected_output: 'retry answer 1' },
  { input: 'retry probe 2', expected_output: 'retry answer 2' },
  { input: 'retry probe 3', expected_output: 'retry answer 3' },
  { input: 'retry probe 4', expected_output: 'retry answer 4' },
];

/** How many stored items carry exactly this content, keys and all. */
function countMatching(
  items: Array<{ data: Record<string, unknown> }>,
  content: Record<string, string>,
): number {
  return items.filter((item) =>
    Object.entries(content).every(([key, value]) => item.data[key] === value),
  ).length;
}

test.describe('Dataset insert — retry after a failed funnel', { tag: ['@area:datasets'] }, () => {
  test(
    'items an aborted insert never sent are stored by the retry, and the retry is still deduplicated',
    {
      tag: [
        '@t2-cuj',
        '@cap:datasets.sdk-round-trip',
        // The last step reads the Version history tab's Item count and its
        // "+ N" Changes tag. Untagged, that would be invisible coverage.
        '@cap:datasets.version-history-view',
      ],
    },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace, page }) => {
      const datasetName = `${testNamespace}-retry-funnel`;

      const { datasetId, result } = await test.step(
        'Abort an insert part-way, then retry the same items on the same Dataset object',
        async () => {
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'retry after a failed insert funnel (opik#9393)',
          });
          // Registered the moment the id exists: the dataset is created
          // mid-test, so no seed fixture can know it upfront, and datasets do
          // not cascade with their project.
          registerDatasetCleanup(created.id, datasetName);

          // One request: the three inserts must share one `Dataset` object, and
          // the bridge builds a fresh client per call. A backend-fetched
          // `Dataset` always starts with its cache unsynced, so split across
          // requests the retry would re-sync regardless and the test could not
          // fail.
          const res = await sdkClient.python.insertDatasetItemsRetryAfterFailedFunnel({
            project_name: project.name,
            dataset_name: datasetName,
            items: ITEMS,
          });
          return { datasetId: created.id, result: res };
        },
      );

      await test.step('The first insert really did abort, and really did store nothing', async () => {
        // Both halves of the precondition, asserted before anything is claimed
        // about the retry. If the insert had completed, or if its items had
        // landed anyway, "the retry stored four items" would be true for a
        // reason that has nothing to do with the fix — and the spec would read
        // as coverage forever.
        expect(result.aborted_insert_error, 'the insert raised rather than completing').toContain(
          'the funnel never completed',
        );
        expect(
          result.items_after_aborted_insert,
          'nothing the aborted insert hashed reached the backend',
        ).toBe(0);
      });

      await test.step('The retry stores every item the aborted insert lost', async () => {
        // The regression: without the cache invalidation this is still 0,
        // because the retry recognises all four items as ones it has "already
        // seen" and sends nothing at all.
        expect(
          result.items_after_retry,
          'the retry re-synced against the backend and sent all four items',
        ).toBe(ITEMS.length);
      });

      await test.step('A third insert of the same items changes nothing — dedup still works', async () => {
        // The other direction of the same fix. Invalidating the cache must not
        // amount to turning deduplication off from then on; after a completed
        // insert the object's cache describes the backend again, so the repeat
        // has to collapse to nothing.
        expect(result.items_after_repeat, 'the repeat added no items').toBe(ITEMS.length);
      });

      const storedIds = await test.step('The stored items are the four that were sent', async () => {
        const items = await backendClient.listDatasetItemsWithData(datasetId);
        // Length first, then content: "all four contents are present" would
        // also hold if a fifth, unrelated row had been stored.
        expect(items, 'exactly four items').toHaveLength(ITEMS.length);
        for (const item of ITEMS) {
          expect(countMatching(items, item), `one row carries ${item.input}`).toBe(1);
        }
        const ids = items.map((item) => item.id);
        expect(new Set(ids).size, 'each under its own id').toBe(ITEMS.length);
        return ids;
      });

      await test.step('One version was cut, counting all four items as additions', async () => {
        // One version, not three. The aborted insert flushed no batch so cut
        // nothing, and the repeat deduplicated to nothing so cut nothing
        // either; only the retry wrote. Asserted as the whole version list so
        // a spurious empty version fails here.
        const versions = await backendClient.getDatasetVersions(datasetId);
        expect(
          versions.map((v) => ({
            versionName: v.versionName,
            itemsTotal: v.itemsTotal,
            itemsAdded: v.itemsAdded,
            itemsModified: v.itemsModified,
          })),
        ).toEqual([{ versionName: 'v1', itemsTotal: 4, itemsAdded: 4, itemsModified: 0 }]);
      });

      await test.step('The Records tab shows the four rows and Version history reads 4 / + 4', async () => {
        const datasets = new DatasetsPage(page);
        await datasets.goto(project.id);
        await datasets.waitForReady();
        const items = await datasets.openDatasetByName(datasetName);
        await items.waitForReady();

        await expect(items.itemRows(), 'four rows render').toHaveCount(ITEMS.length);
        // By id rather than by cell text: this is what shows the grid is
        // rendering the four items the API reported, not four of anything.
        expect((await items.itemRowIds()).sort()).toEqual([...storedIds].sort());

        await items.openVersionHistory();
        await expect(items.versionItemCount('v1'), 'Item count reads 4').toHaveText('4');
        await expect(items.versionChangeSummary('v1'), 'and the Changes tag reads + 4').toHaveText(
          '+ 4',
        );
      });
    },
  );
});
