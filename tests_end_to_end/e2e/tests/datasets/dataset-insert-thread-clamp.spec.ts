import { test, expect } from '@e2e/fixtures';
import type { DatasetVersionRef } from '@e2e/core/backend';

/**
 * `Dataset.insert(num_threads=...)` now clamps at
 * `DATASET_ITEMS_WRITE_MAX_THREADS` (32) instead of letting the value through,
 * matching what the read path has always done. A caller asking for more workers
 * than the cap must be served, not refused — and every item must still land,
 * because the count also sizes the upload's byte budget.
 *
 * The estate covers the read side of exactly this: `dataset-parallel-item-read.
 * spec.ts` drives `get_items(num_threads=1000)` and asserts it clamps rather
 * than raises. The write side has only `test_dataset_client.py`'s unit test, so
 * the asymmetry is real — a write path that started rejecting an over-cap
 * count, or clamped it and then lost a batch, would break no test here.
 *
 * API-level throughout, deliberately: the claim is about what the SDK does with
 * an argument and what the backend ends up holding. No UI surfaces a thread
 * count, so driving a page to observe this second-hand would be slower and
 * flakier without asserting anything more.
 *
 * SEED_SIZE is above the SDK's 1000-item batch size, so the insert is genuinely
 * multi-batch and the workers have something to distribute; at one batch a
 * clamp is unobservable either way.
 */
const SEED_SIZE = 2500;

/** Comfortably above the 32-thread cap, so the clamp is what is under test. */
const OVER_CAP_THREADS = 1000;

/**
 * Values `insert()` documents as rejected, with the pattern the message each
 * raises has to match. Rejection happens before the shape pre-pass and before
 * any batch is sent, so neither leaves a partial write behind.
 */
const REJECTED_THREAD_COUNTS = [0, -1];
const REJECTION_PATTERN = /num_threads must be a positive integer/;

/**
 * The four counters the estate compares — the same shape
 * `dataset-insert-deduplication.spec.ts` and `dataset-version-counters.spec.ts`
 * use. `itemsDeleted` is deliberately not among them, here or anywhere else in
 * the suite: nothing in these candidates is about deletion, and a delta version
 * cut by an insert was observed reporting a non-zero one (see the PR body), so
 * asserting it either way would encode a guess rather than a verified
 * expectation.
 */
function counters(versions: DatasetVersionRef[]) {
  return versions
    .map((v) => ({
      versionName: v.versionName,
      itemsTotal: v.itemsTotal,
      itemsAdded: v.itemsAdded,
      itemsModified: v.itemsModified,
    }))
    .sort((a, b) => a.versionName.localeCompare(b.versionName));
}

test.describe('Dataset insert — write thread count', { tag: ['@area:datasets'] }, () => {
  test(
    `insert(num_threads=${OVER_CAP_THREADS}) is clamped rather than rejected, and still stores every item`,
    // `sdk-round-trip` is where the estate files Dataset.insert's own argument
    // semantics — the deduplication flag and get_items' documented ValueErrors
    // sit under it already — and `version-history-view` covers the version
    // counters the last step compares, as the API-level
    // `dataset-version-concurrent-writes.spec.ts` does. Not
    // `create-dataset-sdk`: this creates a dataset only to have somewhere to
    // insert into, and tagging that would file the insert coverage under a
    // capability nothing here exercises.
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip', '@cap:datasets.version-history-view'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace }) => {
      /**
       * A multi-batch insert against a cloud backend outruns the default budget
       * when the workspace is being rate-limited. Measured at ~15s against
       * staging, so this is headroom rather than need.
       */
      test.slow();

      const datasetName = `${testNamespace}-over-cap`;

      const datasetId = await test.step(
        `Insert ${SEED_SIZE} items asking for ${OVER_CAP_THREADS} workers`,
        async () => {
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: `insert with num_threads=${OVER_CAP_THREADS}`,
          });
          registerDatasetCleanup(created.id, datasetName);

          const result = await sdkClient.python.insertDatasetItems({
            project_name: project.name,
            dataset_name: datasetName,
            // Every item differs by index: insert() drops duplicates by content
            // hash, so repeated items would store fewer than SEED_SIZE and
            // quietly shrink the upload below one batch.
            items: Array.from({ length: SEED_SIZE }, (_, seq) => ({
              input: `over-cap input ${seq}`,
              expected_output: `over-cap output ${seq}`,
              seq,
            })),
            num_threads: OVER_CAP_THREADS,
          });

          // The clamp, stated as the SDK exposes it: an over-cap count is
          // accepted. The worker count itself is internal to the upload and not
          // observable from here, so what this asserts is that the call was
          // served and that nothing was lost serving it — which is the part a
          // caller would notice going wrong.
          expect(result.value_error, 'an over-cap thread count is clamped, not refused').toBeNull();
          expect(result.inserted).toBe(SEED_SIZE);

          return created.id;
        },
      );

      await test.step(`All ${SEED_SIZE} items are stored, each exactly once`, async () => {
        const itemIds = await backendClient.listDatasetItemIds(datasetId);
        expect(itemIds, 'every item sent reached the backend').toHaveLength(SEED_SIZE);
        expect(new Set(itemIds).size, 'and no batch was uploaded twice').toBe(SEED_SIZE);
      });

      await test.step('The upload landed in a single version reporting every item', async () => {
        // One insert is one version however many batches it split into, and
        // however many workers pushed them. The whole list is compared, not a
        // lookup of v1 inside it, so a second version cut by a re-sent batch
        // fails here rather than hiding behind a correct-looking v1.
        expect(counters(await backendClient.getDatasetVersions(datasetId))).toEqual([
          {
            versionName: 'v1',
            itemsTotal: SEED_SIZE,
            itemsAdded: SEED_SIZE,
            itemsModified: 0,
          },
        ]);
      });
    },
  );

  test(
    'insert() rejects a non-positive num_threads and writes nothing when it does',
    // Same pair, and for the same reasons: the rejection is insert's own
    // argument validation, and the last two steps compare the whole version
    // list either side of it.
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip', '@cap:datasets.version-history-view'] },
    async ({ dataset, project, sdkClient, backendClient }) => {
      // Validation runs before the shape pre-pass and before the first request,
      // so the shared 3-item dataset fixture is enough — the size of the
      // dataset is irrelevant to whether the argument is refused.
      const seeded = dataset.items.length;

      for (const numThreads of REJECTED_THREAD_COUNTS) {
        await test.step(`num_threads=${numThreads} raises ValueError`, async () => {
          const result = await sdkClient.python.insertDatasetItems({
            project_name: project.name,
            dataset_name: dataset.name,
            items: [{ input: `rejected at ${numThreads}`, expected_output: 'never stored' }],
            num_threads: numThreads,
          });
          expect(
            result.value_error,
            `num_threads=${numThreads} must be rejected, not silently accepted`,
          ).not.toBeNull();
          expect(result.value_error!).toMatch(REJECTION_PATTERN);
          expect(result.inserted, 'a rejected insert sends nothing').toBe(0);
        });
      }

      await test.step('Neither rejected call left anything behind', async () => {
        const itemIds = await backendClient.listDatasetItemIds(dataset.id);
        expect(itemIds, 'the dataset still holds only what the fixture seeded').toHaveLength(
          seeded,
        );
        expect(counters(await backendClient.getDatasetVersions(dataset.id))).toEqual([
          {
            versionName: 'v1',
            itemsTotal: seeded,
            itemsAdded: seeded,
            itemsModified: 0,
          },
        ]);
      });

      await test.step('A valid num_threads on the same dataset still inserts', async () => {
        // Without this the step above would pass just as well against a bridge
        // that refused every insert, or a dataset nothing could be written to.
        const result = await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: dataset.name,
          items: [{ input: 'accepted at 2', expected_output: 'stored' }],
          num_threads: 2,
        });
        expect(result.value_error).toBeNull();

        const itemIds = await backendClient.listDatasetItemIds(dataset.id);
        expect(itemIds).toHaveLength(seeded + 1);
        expect(counters(await backendClient.getDatasetVersions(dataset.id))).toEqual([
          {
            versionName: 'v1',
            itemsTotal: seeded,
            itemsAdded: seeded,
            itemsModified: 0,
          },
          {
            versionName: 'v2',
            itemsTotal: seeded + 1,
            itemsAdded: 1,
            itemsModified: 0,
          },
        ]);
      });
    },
  );
});
