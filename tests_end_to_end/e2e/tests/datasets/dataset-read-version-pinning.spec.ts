import { test, expect } from '@e2e/fixtures';

/**
 * The SDK's parallel item reader (OPIK-8253) addresses pages by offset, and the
 * items endpoint sorts newest id first. So an item inserted while a read is in
 * progress lands at offset 0 and shifts every page not yet fetched: the read
 * returns one item twice and skips another, with no error raised anywhere and
 * nothing in the result to show it happened. `Dataset._resolve_read_version()`
 * is what prevents it — the whole read is pinned to the version that was latest
 * when iteration began, so a concurrent write cannot move it.
 *
 * The property is exact, not statistical: zero duplicates, zero losses, zero
 * intruders. Nothing in the estate covers it — the reads in
 * `dataset-parallel-item-read.spec.ts` run against a dataset nobody is writing
 * to, which is precisely the case where an unpinned read also passes.
 *
 * API-level, and deterministic rather than raced. The bridge consumes
 * PAUSE_AFTER_CHUNKS chunks, runs the insert to completion, and only then reads
 * the remaining pages — so the write is committed before the pages that would
 * have shifted are fetched, every run, instead of whenever the timing happens
 * to work out. `CHUNK_SIZE * (PAUSE_AFTER_CHUNKS + 2 * num_threads)` is 700
 * items against a SEED_SIZE of 4000, so the reader's look-ahead is nowhere near
 * having fetched the dataset when the insert lands.
 */
const SEED_SIZE = 4000;
const INSERT_SIZE = 500;
const CHUNK_SIZE = 100;
const PAUSE_AFTER_CHUNKS = 5;

const EXPECTED_CHUNKS = SEED_SIZE / CHUNK_SIZE;

function seedItems(count: number, offset: number, label: string) {
  return Array.from({ length: count }, (_, index) => ({
    input: `${label} input ${offset + index}`,
    expected_output: `${label} output ${offset + index}`,
    // Dataset.insert() drops duplicates by content hash; the offset keeps the
    // mid-read batch distinct from the seed so all INSERT_SIZE items are stored.
    seq: offset + index,
  }));
}

test.describe('Dataset item read — version pinning', { tag: ['@area:datasets'] }, () => {
  /**
   * Seeding 4000 items and reading them back in 40 chunks outruns the default
   * budget. Measured at ~2.0 min against staging, most of it the seed.
   */
  test.slow();

  test(
    'An insert committed mid-read cannot duplicate, skip or leak an item into the read that was already running',
    { tag: ['@t2-cuj', '@cap:datasets.sdk-round-trip'] },
    async ({ project, sdkClient, backendClient, registerDatasetCleanup, testNamespace }) => {
      const datasetName = `${testNamespace}-pinned`;

      const datasetId = await test.step(`Seed a dataset with ${SEED_SIZE} items`, async () => {
        const created = await sdkClient.python.createDataset({
          project_name: project.name,
          name: datasetName,
          description: 'read version pinning',
        });
        registerDatasetCleanup(created.id, datasetName);
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
          items: seedItems(SEED_SIZE, 0, 'seed'),
        });
        return created.id;
      });

      const beforeIds = await test.step('Record the ids the read is entitled to return', async () => {
        const ids = await backendClient.listDatasetItemIds(datasetId);
        expect(ids, 'the seed stored every item').toHaveLength(SEED_SIZE);
        expect(new Set(ids).size, 'and stored none of them twice').toBe(SEED_SIZE);
        return ids;
      });

      const result = await test.step(
        `Read in ${CHUNK_SIZE}-item chunks, inserting ${INSERT_SIZE} more after chunk ${PAUSE_AFTER_CHUNKS}`,
        async () => {
          return sdkClient.python.readDatasetItemsWithMidReadInsert({
            project_name: project.name,
            dataset_name: datasetName,
            items: seedItems(INSERT_SIZE, SEED_SIZE, 'mid-read'),
            chunk_size: CHUNK_SIZE,
            num_threads: 1,
            pause_after_chunks: PAUSE_AFTER_CHUNKS,
          });
        },
      );

      await test.step('The insert really did land in the middle of the read', async () => {
        // Without this the assertions below would hold just as well for a read
        // that had already finished before the write started — which is the
        // scenario every implementation passes.
        //
        // What rules that out is the bridge rather than the echo below: it 422s
        // if the read ran out of chunks before the pause, so a 200 carrying
        // EXPECTED_CHUNKS chunks means the insert ran with
        // EXPECTED_CHUNKS - PAUSE_AFTER_CHUNKS pages still unfetched.
        expect(result.inserted, 'the write was issued').toBe(INSERT_SIZE);
        expect(
          result.chunks_before_insert,
          'the bridge paused where it was asked to',
        ).toBe(PAUSE_AFTER_CHUNKS);
        expect(
          result.chunk_sizes,
          `every page came back full, and pages remained to be fetched after chunk ${PAUSE_AFTER_CHUNKS}`,
        ).toEqual(new Array(EXPECTED_CHUNKS).fill(CHUNK_SIZE));
      });

      await test.step('The read returned exactly the pre-insert dataset, in order', async () => {
        // Three distinct failures collapse into this one comparison: an item
        // returned twice, an item skipped, and one of the new items appearing
        // in a read that was pinned before they existed.
        expect(result.item_ids, 'no item was duplicated or skipped').toHaveLength(SEED_SIZE);
        expect(new Set(result.item_ids).size, 'every id came back once').toBe(SEED_SIZE);
        expect(result.item_ids, 'the pinned read is unmoved by the insert').toEqual(beforeIds);
      });

      await test.step('And the insert was real — a fresh read sees all of it', async () => {
        // The pinned read seeing 4000 items would also be satisfied by an
        // insert that silently failed. This is what rules that out.
        const afterIds = await backendClient.listDatasetItemIds(datasetId);
        expect(afterIds).toHaveLength(SEED_SIZE + INSERT_SIZE);
        expect(new Set(afterIds).size).toBe(SEED_SIZE + INSERT_SIZE);
        const afterIdSet = new Set(afterIds);
        expect(
          beforeIds.filter((id) => !afterIdSet.has(id)),
          'the insert added items rather than replacing any',
        ).toEqual([]);

        const { item_ids, value_error } = await sdkClient.python.readDatasetItems({
          project_name: project.name,
          dataset_name: datasetName,
        });
        expect(value_error).toBeNull();
        expect(item_ids, 'a read started now is pinned to the new version').toHaveLength(
          SEED_SIZE + INSERT_SIZE,
        );
      });
    },
  );
});
