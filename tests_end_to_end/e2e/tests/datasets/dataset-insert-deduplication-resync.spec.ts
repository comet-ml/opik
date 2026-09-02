import { test, expect } from '@e2e/fixtures';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * `Dataset.insert(..., deduplication=False)` hashes nothing, so after it runs
 * the Dataset object's local content-hash cache no longer describes the
 * backend. The SDK marks the cache stale on purpose, and the next DEDUPLICATED
 * insert on that same object re-syncs it before comparing.
 *
 * If that invalidation ever stops firing, nothing errors and no request fails:
 * the next deduplicated insert compares against a cache that is missing
 * everything the dedup-off call wrote, decides those items are new, and stores
 * a second copy of every one of them. Deduplication is then silently off for
 * the rest of the process's life. Only a mock-based unit test covers it today.
 *
 * ## Why the sequence has four steps
 *
 * The priming insert is load-bearing, not setup noise. `get_dataset()` hands
 * back an object whose cache is ALREADY marked stale, so a sequence that
 * opened with the dedup-off insert would re-sync at step 3 whether or not the
 * SDK invalidated anything — a spec that cannot fail. Priming with a
 * deduplicated insert first is what leaves the cache marked fresh, so the
 * dedup-off insert has something to invalidate:
 *
 *   1. insert 5 items, dedup ON   -> cache synced and marked fresh, holds 5
 *   2. insert 10 NEW items, OFF   -> nothing hashed; cache must be re-marked stale
 *   3. re-insert those 10, ON     -> must re-sync (15 hashes) and send NOTHING
 *   4. those 10 + 3 new, ON       -> must send exactly the 3
 *
 * Step 3 is the assertion. With the invalidation: no version is cut and the
 * dataset still holds 15 rows. Without it: the stale 5-hash cache misses all
 * ten, a third version lands with 10 additions, and the dataset holds 25.
 * Step 4 is the other half — it rules out a "fix" that suppresses everything.
 *
 * The whole sequence runs against ONE in-process Dataset object
 * (`insertDatasetItemSequence`). The cache is per-object, so re-resolving the
 * dataset between calls — which is what repeated `insertDatasetItems` calls
 * do — would re-sync on its own and destroy the distinction being asserted.
 */
const PRIME_SIZE = 5;
const DEDUP_OFF_SIZE = 10;
const EXTRA_SIZE = 3;

const AFTER_PRIME = PRIME_SIZE;
const AFTER_DEDUP_OFF = PRIME_SIZE + DEDUP_OFF_SIZE;
const AFTER_EXTRA = AFTER_DEDUP_OFF + EXTRA_SIZE;

/**
 * Items carry no explicit id: the SDK mints a fresh one per item on every
 * call, so deduplication here can only be happening on content hash — which is
 * the mechanism under test. Ids would have let the backend upsert instead and
 * hidden a broken cache behind a correct row count.
 */
function seedItems(prefix: string, from: number, to: number) {
  return Array.from({ length: to - from }, (_, offset) => ({
    input: `${prefix} input ${from + offset}`,
    expected_output: `${prefix} output ${from + offset}`,
  }));
}

/** The `${prefix} input N` items the dedup-off step wrote, re-sent verbatim. */
const dedupOffItems = () => seedItems('batch', 0, DEDUP_OFF_SIZE);

test.describe(
  'Dataset insert — hash cache re-sync after a deduplication=False insert',
  { tag: ['@area:datasets'] },
  () => {
    test(
      'A deduplication=False insert forces the next deduplicated insert to re-sync hashes, so re-sent items are still suppressed',
      {
        tag: [
          '@t2-cuj',
          '@cap:datasets.sdk-round-trip',
          '@cap:datasets.version-history-view',
        ],
      },
      async ({
        project,
        sdkClient,
        backendClient,
        registerDatasetCleanup,
        testNamespace,
        page,
      }) => {
        const datasetName = `${testNamespace}-dedup-resync`;

        const datasetId = await test.step('Create an empty dataset', async () => {
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'deduplication=False invalidates the local hash cache',
          });
          // Registered as soon as the id exists: the dataset is created
          // mid-test, so no seed fixture can know it upfront, and datasets do
          // not cascade with their project.
          registerDatasetCleanup(created.id, datasetName);
          return created.id;
        });

        await test.step(
          'Run all four inserts against one Dataset object: prime, dedup-off, re-insert, re-insert + 3 new',
          async () => {
            const result = await sdkClient.python.insertDatasetItemSequence({
              project_name: project.name,
              dataset_name: datasetName,
              steps: [
                { items: seedItems('prime', 0, PRIME_SIZE) },
                { items: dedupOffItems(), deduplication: false },
                { items: dedupOffItems() },
                {
                  items: [
                    ...dedupOffItems(),
                    ...seedItems('extra', 0, EXTRA_SIZE),
                  ],
                },
              ],
            });
            expect(result.steps_run, 'the whole sequence ran').toBe(4);
          },
        );

        await test.step(
          `The dataset holds ${AFTER_EXTRA} rows — the re-insert stored nothing, the 3 new items stored once`,
          async () => {
            const itemIds = await backendClient.listDatasetItemIds(datasetId);
            // 25 here would mean step 3 re-stored all ten: the re-sync did not
            // fire and deduplication is silently broken for this object.
            expect(itemIds, 'no item was stored twice').toHaveLength(AFTER_EXTRA);
            expect(new Set(itemIds).size, 'and no id was reused').toBe(AFTER_EXTRA);
          },
        );

        await test.step(
          'Only three inserts sent anything, and their counters account for every row',
          async () => {
            const versions = await backendClient.getDatasetVersions(datasetId);
            // Four insert() calls, three versions: the re-insert at step 3 must
            // dedup away completely, and an insert that sends no batch cuts no
            // version at all.
            expect(versions, 'the re-insert cut no version of its own').toHaveLength(3);

            const byName = new Map(versions.map((v) => [v.versionName, v]));
            expect([...byName.keys()].sort(), 'v1..v3 and nothing else').toEqual([
              'v1',
              'v2',
              'v3',
            ]);

            const v1 = byName.get('v1')!;
            expect(v1.itemsAdded, 'the priming insert').toBe(PRIME_SIZE);
            expect(v1.itemsTotal).toBe(AFTER_PRIME);
            expect(v1.itemsModified).toBe(0);

            const v2 = byName.get('v2')!;
            expect(v2.itemsAdded, 'the deduplication=False insert').toBe(DEDUP_OFF_SIZE);
            expect(v2.itemsTotal).toBe(AFTER_DEDUP_OFF);
            expect(v2.itemsModified).toBe(0);

            // The other half of the claim: the re-synced cache suppresses the
            // ten repeats WITHOUT also suppressing the three genuinely new
            // items. A cache that over-matched would show 0 additions here.
            const v3 = byName.get('v3')!;
            expect(v3.itemsAdded, 'only the 3 genuinely new items').toBe(EXTRA_SIZE);
            expect(v3.itemsTotal).toBe(AFTER_EXTRA);
            expect(v3.itemsModified, 'the repeats are not modifications either').toBe(0);
            expect(v3.isLatest).toBe(true);

            expect(
              versions.filter((v) => v.isLatest),
              'exactly one version is the latest',
            ).toHaveLength(1);
          },
        );

        await test.step('The Version history tab renders those totals as "Item count"', async () => {
          const datasets = new DatasetsPage(page);
          await datasets.goto(project.id);
          await datasets.waitForReady();
          const items = await datasets.openDatasetByName(datasetName);
          await items.waitForReady();
          await items.openVersionHistory();

          await expect(items.versionItemCount('v1')).toHaveText(String(AFTER_PRIME));
          await expect(items.versionItemCount('v2')).toHaveText(String(AFTER_DEDUP_OFF));
          await expect(items.versionItemCount('v3')).toHaveText(String(AFTER_EXTRA));
        });
      },
    );
  },
);
