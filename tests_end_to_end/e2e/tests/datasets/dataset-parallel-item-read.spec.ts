import { test, expect } from '@e2e/fixtures';

/**
 * OPIK-8253 replaced the Python SDK's cursor-chained item stream with a
 * parallel fan-out over the paginated items endpoint: `get_items()` fetches the
 * first page to learn the total, then reads the rest concurrently and
 * reassembles them (`parallel_items_reader.stream_item_chunks`). A page dropped
 * or reassembled out of order corrupts an evaluation silently — no error is
 * raised anywhere, the caller just gets the wrong items — so the assertion has
 * to be on the list, not the set: the same 5000 ids in a different order is a
 * failure, and `toEqual` on an array is what sees it.
 *
 * The fan-out only runs on a dataset bigger than one page. Every existing
 * `get_items()` call in the estate is against a 2- or 3-item dataset
 * (`dataset-crud-smoke.spec.ts`, the `dataset` fixture), which is a single page
 * — so nothing here exercises the concurrent path at all today.
 *
 * API-level throughout, deliberately: the claim is about what the SDK returns,
 * and no UI reads a dataset through `get_items()`.
 *
 * SEED_SIZE is three pages at the SDK's default `chunk_size` of 2000, so the
 * default call fans out over two concurrent pages rather than degenerating to a
 * single extra fetch.
 */
const SEED_SIZE = 5000;

/** Two `nb_samples` values: one inside the first page, one straddling it. */
const SAMPLE_WITHIN_FIRST_PAGE = 500;
const SAMPLE_ACROSS_PAGES = 2200;

/**
 * Read settings that must all return the identical list. `chunk_size: 500` with
 * 8 threads is 10 pages over 8 workers, the deepest fan-out available under the
 * 2000-item chunk cap; `num_threads: 1000` is above the SDK's 32-thread cap and
 * must clamp rather than raise.
 */
const EQUIVALENT_READS = [
  { label: 'the defaults', args: {} },
  { label: 'num_threads=1 (sequential)', args: { num_threads: 1 } },
  { label: 'chunk_size=500, num_threads=8', args: { chunk_size: 500, num_threads: 8 } },
  { label: 'num_threads=1000 (clamped)', args: { num_threads: 1000 } },
];

/**
 * Arguments `stream_items()` documents as rejected, with the message each one
 * raises. `nb_samples=0` is the upgrade hazard: it used to be accepted, so a
 * caller passing it now gets a ValueError where it previously got items.
 */
const REJECTED_ARGS = [
  { label: 'nb_samples=0', args: { nb_samples: 0 }, message: /nb_samples must be a positive integer/ },
  { label: 'nb_samples=-1', args: { nb_samples: -1 }, message: /nb_samples must be a positive integer/ },
  { label: 'num_threads=0', args: { num_threads: 0 }, message: /num_threads must be a positive integer/ },
  { label: 'chunk_size=0', args: { chunk_size: 0 }, message: /chunk_size must be a positive integer/ },
  { label: 'chunk_size=2001', args: { chunk_size: 2001 }, message: /chunk_size must not exceed 2000/ },
];

test.describe('Dataset items — parallel SDK read', { tag: ['@area:datasets'] }, () => {
  test(
    'get_items() returns every item of a multi-page dataset exactly once, in the same order, whatever the thread and chunk settings',
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace }) => {
      /**
       * Seeding 5000 items against a cloud backend outruns the default budget.
       * Measured at ~2.2 min against staging, of which ~45s was the SDK backing
       * off a 429 — the reads themselves are seconds. Only the first test needs
       * this; the validation one below runs on the shared 3-item fixture.
       */
      test.slow();

      const datasetName = `${testNamespace}-multipage`;

      const datasetId = await test.step(`Seed a dataset with ${SEED_SIZE} items`, async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'parallel item read',
        });
        registerDatasetCleanup(created.id, datasetName);
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          // Every item differs by index: Dataset.insert() drops duplicates by
          // content hash, so identical items would seed fewer than SEED_SIZE
          // and quietly shrink the read below one page.
          items: Array.from({ length: SEED_SIZE }, (_, seq) => ({
            input: `item ${seq}`,
            expected_output: `output ${seq}`,
            seq,
          })),
        });
        return created.id;
      });

      const expectedIds = await test.step(
        'Enumerate the dataset directly over REST — the ground truth',
        async () => {
          // Read page by page through the items endpoint without the SDK, so
          // the comparison below is against the backend rather than against
          // another run of the code under test.
          const ids = await backendClient.listDatasetItemIds(datasetId);
          expect(ids, 'the seed stored every item').toHaveLength(SEED_SIZE);
          expect(new Set(ids).size, 'and stored none of them twice').toBe(SEED_SIZE);
          return ids;
        },
      );

      for (const { label, args } of EQUIVALENT_READS) {
        await test.step(`get_items() with ${label} returns the same list`, async () => {
          const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
            project_name: project.name,
            dataset_name: datasetName,
            ...args,
          });
          expect(value_error, `${label} must not be rejected`).toBeNull();
          // List equality, not set equality: a fan-out that reassembles its
          // pages in the wrong order returns the right items in the wrong
          // sequence, and every downstream evaluation misaligns.
          expect(item_ids, `${label}: same items, same order as REST`).toEqual(expectedIds);
        });
      }

      for (const nbSamples of [SAMPLE_WITHIN_FIRST_PAGE, SAMPLE_ACROSS_PAGES]) {
        await test.step(`nb_samples=${nbSamples} returns that many items, from the start`, async () => {
          const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
            project_name: project.name,
            dataset_name: datasetName,
            nb_samples: nbSamples,
          });
          expect(value_error).toBeNull();
          // "From the beginning of the dataset" means the endpoint's own order,
          // which is newest id first — so the sample must be an exact prefix of
          // the full read, not merely a subset of it.
          expect(item_ids).toEqual(expectedIds.slice(0, nbSamples));
        });
      }
    },
  );

  test(
    'get_items() rejects the argument values it documents as invalid',
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip'] },
    async ({ dataset, project, sdkClient }) => {
      // Validation runs before any page is fetched, so the shared 3-item
      // dataset fixture is enough — the size of the dataset is irrelevant here.
      for (const { label, args, message } of REJECTED_ARGS) {
        await test.step(`${label} raises ValueError`, async () => {
          const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
            project_name: project.name,
            dataset_name: dataset.name,
            ...args,
          });
          expect(value_error, `${label} must be rejected, not silently accepted`).not.toBeNull();
          expect(value_error!).toMatch(message);
          expect(item_ids, 'a rejected read returns nothing').toEqual([]);
        });
      }

      await test.step('A read with valid arguments still returns the items', async () => {
        // Without this the test above would pass just as well against an SDK
        // that rejected every call.
        const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
          project_name: project.name,
          dataset_name: dataset.name,
        });
        expect(value_error).toBeNull();
        expect(item_ids).toHaveLength(dataset.items.length);
      });
    },
  );
});
