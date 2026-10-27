import { test, expect } from '@e2e/fixtures';
import { RAW_ITEM_COUNT } from '@e2e/fixtures/raw-branch-experiment.fixture';

/**
 * The page size the walk uses.
 *
 * 7 into 30 gives four full pages and a remainder of two, so every boundary is
 * crossed at an offset that divides neither the total nor the page size — the
 * arithmetic an off-by-one slice survives when the numbers are tidy.
 */
const PAGE_SIZE = 7;

/** Big enough to take the whole experiment in one read, for the baseline. */
const UNPAGED_SIZE = 100;

/**
 * Paging the un-aggregated compare read WITH A SORT (opik#8515, OPIK-8274).
 *
 * `compare-deep-paging.spec.ts` already pins this branch as a partition at the
 * shipped default page size, and it is the right spec for that question. It is
 * also, by its own design, entirely UNSORTED: `applyPushTopLimit` reaches
 * `push_top_limit_raw` only for a read with no filters, no search, and sorting
 * by `id` or not at all, and that spec takes the "not at all" arm throughout.
 *
 * Sorting by `id` is the other arm, and nothing exercises it. It is not a
 * cosmetic difference: the pushdown has to compose its Top-N CTE with an
 * explicit ORDER BY rather than fall back to the query's natural order, and a
 * sort that is applied inside the page instead of across the whole result set
 * produces pages that are each internally ordered and collectively wrong — 30
 * rows, no duplicates, right total, wrong order. Every count-based check passes.
 *
 * Both directions, because they are not symmetric in the query: `id DESC` is
 * what the branch does by default, so `id ASC` is the one that has to override
 * it, and an override that silently fails would agree with the default and look
 * ordered.
 *
 * Small on purpose. The 5,000-item seed exists to reach the shipped default
 * page size; this question needs only enough rows to cross several boundaries,
 * and 30 items seed in seconds rather than in seven minutes.
 */
test.describe(
  'Experiments comparison — sorted paging on the un-aggregated branch',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'an id-sorted paged walk is an exact, correctly-ordered partition in both directions',
      { tag: ['@cap:experiments.compare-deep-paging'] },
      async ({ rawBranchExperiment, backendClient }) => {
        const seed = rawBranchExperiment;

        /** One unpaged read, at a size that takes the whole experiment. */
        const readAll = async (direction: 'ASC' | 'DESC' | null) => {
          const answer = await backendClient.compareItemsPage({
            datasetId: seed.datasetId,
            experimentIds: [seed.experimentId],
            size: UNPAGED_SIZE,
            ...(direction === null
              ? {}
              : { sorting: [{ field: 'id', direction }] }),
          });
          expect(
            answer.total,
            `total on the unpaged ${direction ?? 'unsorted'} read`,
          ).toBe(RAW_ITEM_COUNT);
          expect(
            answer.ids,
            `rows on the unpaged ${direction ?? 'unsorted'} read`,
          ).toHaveLength(RAW_ITEM_COUNT);
          return answer.ids;
        };

        /** Walk every page at `PAGE_SIZE`, plus the page after the last. */
        const walk = async (direction: 'ASC' | 'DESC') => {
          const fullPages = Math.floor(RAW_ITEM_COUNT / PAGE_SIZE);
          const remainder = RAW_ITEM_COUNT % PAGE_SIZE;
          const expectedLengths = [
            ...Array.from({ length: fullPages }, () => PAGE_SIZE),
            ...(remainder > 0 ? [remainder] : []),
          ];

          const ids: string[] = [];
          for (const [i, expectedLength] of expectedLengths.entries()) {
            const pageNumber = i + 1;
            const answer = await backendClient.compareItemsPage({
              datasetId: seed.datasetId,
              experimentIds: [seed.experimentId],
              page: pageNumber,
              size: PAGE_SIZE,
              sorting: [{ field: 'id', direction }],
            });
            // The total on EVERY page, not only the first: the count and the
            // rows are two projections of one query, and a slice bug can move
            // one without the other.
            expect(
              answer.total,
              `total on page ${pageNumber} of the ${direction} walk`,
            ).toBe(RAW_ITEM_COUNT);
            expect(
              answer.ids.length,
              `rows on page ${pageNumber} of the ${direction} walk`,
            ).toBe(expectedLength);
            ids.push(...answer.ids);
          }

          // Empty, not short. A reader that clamped the offset instead of
          // running off the end re-serves the last slice here, which is how a
          // client that pages until it sees a short page loops forever.
          const pastEnd = await backendClient.compareItemsPage({
            datasetId: seed.datasetId,
            experimentIds: [seed.experimentId],
            page: expectedLengths.length + 1,
            size: PAGE_SIZE,
            sorting: [{ field: 'id', direction }],
          });
          expect(
            pastEnd.ids,
            `the page after the last of the ${direction} walk must be empty, not a repeat`,
          ).toEqual([]);

          return ids;
        };

        const seededAscending = [...seed.datasetItemIds];

        await test.step('The unsorted read returns every seeded item once', async () => {
          const unsorted = await readAll(null);
          expect(
            [...unsorted].sort(),
            'the unsorted read is exactly the seeded dataset items',
          ).toEqual([...seededAscending].sort());
        });

        const unpaged: Record<'ASC' | 'DESC', string[]> = { ASC: [], DESC: [] };

        for (const direction of ['ASC', 'DESC'] as const) {
          await test.step(`Sorted ${direction}, the unpaged read is in id order`, async () => {
            unpaged[direction] = await readAll(direction);
            // Against the SEED's known order, not merely "sorted": the fixture
            // mints ids at strictly increasing instants, so ascending id order
            // is seed order — which is a fact this test knows independently of
            // whatever the endpoint returns. Comparing the response to a sorted
            // copy of itself would pass on any ordering at all.
            const expected =
              direction === 'ASC' ? seededAscending : [...seededAscending].reverse();
            expect(
              unpaged[direction],
              `the unpaged id-${direction} read must be the seeded items in id-${direction} order`,
            ).toEqual(expected);
          });

          await test.step(`Sorted ${direction}, the paged walk matches it exactly`, async () => {
            const walked = await walk(direction);
            // In order, not sorted: the whole failure this catches is a sort
            // applied within each page rather than across the result set,
            // which yields the right SET on every page and the wrong sequence
            // overall. Sorting either side before comparing would hide it.
            expect(
              walked,
              `paging at ${PAGE_SIZE} sorted id-${direction} must yield the same rows in the ` +
                'same order as the unpaged read — a sort applied per page instead of across ' +
                'the result set passes every count check and fails only here',
            ).toEqual(unpaged[direction]);
          });
        }

        await test.step('The two directions are exact reverses of one another', () => {
          // The cross-check neither direction can make alone: a read that
          // ignored `direction` and always served its natural order would
          // satisfy one of the two comparisons above and fail this.
          expect(
            unpaged.DESC,
            'the id-DESC read must be the id-ASC read reversed',
          ).toEqual([...unpaged.ASC].reverse());
        });

        await test.step('Those reads were taken while the experiment was still un-aggregated', () => {
          // The premise of the whole spec, asserted rather than assumed.
          // `applyPushTopLimit` takes the raw branch only while the experiment
          // has never been denormalized; past the debounce the reads above were
          // served by the aggregated branch, and this test has NOT exercised
          // the pushdown OPIK-8274 rewrote. Failing here says "could not pin
          // the branch", which is the honest outcome — a pass would be coverage
          // of something else under this spec's name.
          const elapsed = Date.now() - seed.writtenAtMs;
          expect(
            elapsed,
            `the paged reads finished ${elapsed}ms after the last experiment-item write; past ` +
              `${seed.rawBranchWindowMs}ms the denormalization job may have run, so ` +
              'push_top_limit_raw is no longer guaranteed to be the branch under test',
          ).toBeLessThan(seed.rawBranchWindowMs);
        });
      },
    );
  },
);
