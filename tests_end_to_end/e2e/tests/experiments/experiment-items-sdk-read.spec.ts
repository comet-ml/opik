import { test, expect } from '@e2e/fixtures';

/** One read's answer, reduced to what a paging bug would disturb. */
interface ReadArm {
  label: string;
  items: Array<{ id: string; dataset_item_id: string; trace_id: string; idx: number | null }>;
}

/**
 * `Experiment.get_items()` across its paging knobs (OPIK-8274 / opik#8491).
 *
 * 8491 replaced a sequential 100-item read with concurrent waves over raw JSON
 * instead of parsed models — the classic shape for rows that are silently
 * dropped, duplicated or reordered when a wave boundary is crossed. The e2e
 * estate has never read experiment items back through the Python SDK at all:
 * it writes them over REST and reads them through the compare grid.
 *
 * Written as a self-consistency check rather than as a comparison against a
 * 2.2.77 baseline, deliberately. A spec that pinned "what the old SDK
 * returned" would need a second SDK installed at run time and would stop being
 * runnable the moment that version aged out; asserting that every arm agrees
 * with every other needs nothing but this release, so it keeps working on
 * every release after it.
 *
 * SCOPE — read this before trusting the tag. The paging below is driven by an
 * explicit `page_size`, because a seed large enough to make the SHIPPED
 * DEFAULT (2,000) page is ~6,300 writes and a shared cloud workspace answers
 * 429 long before that lands. The wave loop, its page-count arithmetic and its
 * concurrent assembly are the same code at any page size, so this does test
 * them — but "a default-configured read of a >2,000-item experiment" is not
 * covered here and stays on the release's manual verification list.
 *
 * API-level by design — this is an SDK read path, and there is no UI that
 * surfaces it.
 */
test.describe(
  'Experiments — SDK experiment-item read',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'every paging arm returns the same rows in the same order',
      { tag: ['@cap:experiments.experiment-item-read'] },
      async ({ experimentItemRead, sdkClient }) => {
        // Seeding 250 rows and then reading them seven ways, two of them
        // deliberately sequential, is well past the 90s default.
        test.setTimeout(600_000);

        const { experimentId, itemCount, seeded } = experimentItemRead;

        const baseline = await test.step('Read at the SDK defaults', async () => {
          const read = await sdkClient.python.readExperimentItems({ experiment_id: experimentId });
          expect(read.count, 'the default read returns every seeded item').toBe(itemCount);
          expect(read.items, 'the default read returns every seeded item').toHaveLength(itemCount);
          return read.items;
        });

        await test.step('The default read is complete, ordered and free of duplicates', async () => {
          // Every row must carry its index. `idx` is nullable because a row
          // whose `dataset_item_data` came back empty or malformed is exactly
          // the corruption this spec is looking for — so it is asserted away
          // here rather than coded around below.
          expect(
            baseline.filter((item) => item.idx === null).length,
            'every item carries the idx its dataset item was seeded with',
          ).toBe(0);

          // Completeness, stated over the SORTED indices. The endpoint behind
          // `get_items()` orders by its own key, not by the `idx` this seed
          // wrote, so the absolute order is not the SDK's to promise and
          // asserting it here would pin the backend's ordering instead of the
          // reader's correctness. Sorted, the check still rules out a dropped
          // row, a duplicated row and an off-by-one at a wave boundary — and
          // the cross-arm comparison below is what pins ORDER, which is the
          // half a concurrent reader can actually get wrong.
          expect(
            [...baseline.map((item) => item.idx)].sort((a, b) => a! - b!),
            'the default read covers idx 0..n-1 exactly once each',
          ).toEqual(Array.from({ length: itemCount }, (_, i) => i));

          expect(
            new Set(baseline.map((item) => item.trace_id)).size,
            'every returned item names a distinct trace',
          ).toBe(itemCount);
          expect(
            new Set(baseline.map((item) => item.dataset_item_id)).size,
            'every returned item names a distinct dataset item',
          ).toBe(itemCount);

          // Then the pairing, which is the half the two counts above cannot
          // see. Completeness and uniqueness are both satisfied by ANY
          // permutation of the seed: a read that handed idx 5's row idx 7's
          // trace_id still returns 250 distinct traces and the indices 0..249
          // once each. Assembling pages concurrently is exactly how a row's
          // fields get mispaired, so the seeded mapping is compared directly —
          // keyed by idx and compared whole, so the diff on failure names the
          // indices that moved rather than only saying a set differed.
          expect(
            [...baseline]
              .sort((a, b) => a.idx! - b.idx!)
              .map((item) => ({
                idx: item.idx,
                datasetItemId: item.dataset_item_id,
                traceId: item.trace_id,
              })),
            'each returned item pairs the dataset item and the trace its idx was seeded with',
          ).toEqual(seeded);
        });

        // Each arm varies one thing against the defaults, so a disagreement
        // names the setting responsible. The small page sizes are what make
        // this a paging test at all: at 25 the read is 10 waves deep.
        const PAGED_SIZE = 25;
        expect(
          itemCount / PAGED_SIZE,
          'the small page size must split the seed into several pages',
        ).toBeGreaterThan(2);

        const arms: ReadArm[] = [];
        for (const [label, args] of [
          ['num_threads=1 (sequential, default page size)', { num_threads: 1 }],
          [`page_size=${PAGED_SIZE} (many waves, default concurrency)`, { page_size: PAGED_SIZE }],
          [
            `page_size=${PAGED_SIZE}, num_threads=1 (many waves, sequential)`,
            { page_size: PAGED_SIZE, num_threads: 1 },
          ],
          ['page_size=100 (uneven final page)', { page_size: 100 }],
          ['page_size=250 (exactly one full page)', { page_size: itemCount }],
          ['page_size=1000 (page larger than the data)', { page_size: 1000 }],
        ] as const) {
          await test.step(`Read with ${label}`, async () => {
            const read = await sdkClient.python.readExperimentItems({
              experiment_id: experimentId,
              ...args,
            });
            expect(read.count, `${label} returns every seeded item`).toBe(itemCount);
            arms.push({ label, items: read.items });
          });
        }

        await test.step('Every arm is identical to the default read', async () => {
          for (const arm of arms) {
            // The whole list, in order, not a spot check: a reader that emitted
            // the right SET of rows in the wrong ORDER is a real regression of
            // this change, and comparing sorted ids would hide it.
            expect(
              arm.items,
              `${arm.label} must return exactly what the default read returned, in the same order`,
            ).toEqual(baseline);
          }
        });

        // `max_results` has its own arithmetic — it has to stop mid-page, which
        // is where a reader that counts pages rather than rows overshoots.
        await test.step('max_results cutting mid-page returns exactly that prefix', async () => {
          const cut = 137;
          expect(
            cut % PAGED_SIZE,
            'the cut must not land on a page boundary or it tests nothing',
          ).not.toBe(0);

          const read = await sdkClient.python.readExperimentItems({
            experiment_id: experimentId,
            max_results: cut,
            page_size: PAGED_SIZE,
          });
          expect(read.count, 'max_results is honoured exactly').toBe(cut);
          expect(
            read.items,
            'a truncated read is the first max_results items of the full read, unchanged',
          ).toEqual(baseline.slice(0, cut));
        });

        // The same cut at a different page size. If truncation were applied
        // per page rather than to the assembled result, these two would
        // disagree — and each on its own would still look self-consistent.
        await test.step('The page size does not change what max_results returns', async () => {
          const cut = 137;
          const read = await sdkClient.python.readExperimentItems({
            experiment_id: experimentId,
            max_results: cut,
            page_size: 100,
            num_threads: 1,
          });
          expect(read.count, 'max_results is honoured at another page size too').toBe(cut);
          expect(
            read.items,
            'the page size must not change which items a truncated read returns',
          ).toEqual(baseline.slice(0, cut));
        });
      },
    );
  },
);
