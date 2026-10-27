import { test, expect } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/** The page size the product ships for this read — `getAllCompareExperimentsItems` uses it. */
const DEFAULT_PAGE_SIZE = 2_000;

/**
 * A second page size whose boundaries land nowhere near the first's: 5,000 rows
 * split 8 ways (7 × 700 + 100) instead of 3 (2 × 2,000 + 1,000). Two paginations
 * of one ordering must yield the same sequence, so reading both is what pins the
 * ORDER of the partition without this spec having to assert which direction the
 * product sorts in.
 */
const ALTERNATE_PAGE_SIZE = 700;

/** The size the compare grid is opened at, and the page that lands on the last row. */
const GRID_PAGE_SIZE = 100;

/**
 * Deep-paging an un-aggregated experiment at the shipped default page size
 * (opik#8575 / opik#8578, OPIK-8274).
 *
 * Both PRs rewrote the same thing: the `push_top_limit_raw` branch of the query
 * behind `GET /v1/private/datasets/{id}/items/experiments/items`, which is the
 * paged read of experiment items — the compare Results tab, the Optimization
 * Studio trial items tab, the Test Suites experiment panel, the compare export,
 * and `Experiment.get_items()` in the Python SDK. 8578 supersedes 8575's SQL
 * form, so a partition assertion on this branch covers both.
 *
 * WHAT THIS ADDS over what already existed. The only automated partition check
 * on this branch is `ExperimentAggregatesIntegrationTest#rawBranchPaging*`,
 * which runs 7 items at page size 2. In the e2e estate,
 * `experiment-items-sdk-read.spec.ts` pages the same endpoint at explicit sizes
 * 25/100/250/1000 over 250 items and says in its own header that the SHIPPED
 * DEFAULT (2,000) is out of scope — that a seed large enough to cross it "is
 * ~6,300 writes and a shared cloud workspace answers 429 long before that
 * lands", and that a default-configured read of a >2,000-item experiment "stays
 * on the release's manual verification list". This spec is that manual item,
 * automated: 5,000 items, read at 2,000, which is the first automated coverage
 * of this endpoint at a non-zero OFFSET at the size real clients use.
 * `compare-export-all-rows.spec.ts` reads 250 rows at `size: 2000` — one big
 * page, silent on `OFFSET > 0`.
 *
 * WHY IT IS ONE TEST with phases rather than several. The seed is ~7 minutes of
 * mostly waiting (see the fixture), and a test-scoped fixture would pay that
 * again per test. `experiment-items-sdk-read.spec.ts` is one test for the same
 * reason.
 *
 * HOW THE REWRITTEN BRANCH IS PINNED, and the limit of it.
 * `applyPushTopLimit` reaches `push_top_limit_raw` only when
 * `hasRaw && !hasAggregated` — and `AggregatedExperimentCounts.hasAggregated()`
 * is false only while `aggregated == 0`, i.e. before the denormalization job has
 * run against that experiment even once. It additionally requires no filters, no
 * search, and sorting by `id` or not at all, which is why every read in phases 1
 * and 2 is unfiltered, unsearched and unsorted (and why
 * `compare-json-key-sorting.spec.ts`, which sorts by `output.<key>`, turns the
 * branch off entirely and is no evidence for this change).
 *
 * `experimentDenormalization.debounceDelay` is 1m and every write resets it, so
 * the fixture writes the read target's experiment items LAST, over dataset items
 * and traces already proven queryable, and hands over the instant the last write
 * landed. Phases 1 and 2 then run inside that window, and phase 3 asserts they
 * did.
 *
 * Stated plainly: **nothing in the response identifies which branch served it.**
 * The timing argument is inference from the backend source, not an observation.
 * Phase 3 is what keeps it from being a silent assumption — without it, a slow
 * day would move these reads onto the aggregated branch and the spec would still
 * go green, reading as coverage of a branch it never touched.
 *
 * The last two phases are deliberately OUTSIDE that window and make no branch
 * claim. They are here because their value does not depend on the branch: the
 * compare grid's paging arithmetic and slice must agree with the API at a deep
 * offset, and `Experiment.get_items()` must page correctly at its own default —
 * the gap `experiment-items-sdk-read.spec.ts` leaves open. Keeping both after the
 * window assertion also stops their latency from eating the window the
 * branch-pinned phases need.
 *
 * The browser phase runs BEFORE the SDK read, which is not arbitrary: the SDK
 * read pulls 5,000 rows in one go, and doing that first was observed to leave the
 * workspace rate limiter refusing the project read the compare route depends on,
 * so the app rendered "Something went wrong" and the grid never issued a request
 * at all. The POM retries that panel, but not provoking it is better than
 * recovering from it.
 */
test.describe(
  'Experiments — deep paging at the shipped default page size',
  { tag: ['@t3-nightly', '@area:experiments'] },
  () => {
    // On the describe, not `test.setTimeout()` in the body: the fixture seeds
    // 5,000 dataset items, 5,000 traces and 10,000 experiment-item links in
    // paced batches — roughly 7 minutes — and fixture setup runs BEFORE the body,
    // so a budget raised inside the body is never in force while the seed is
    // being built. Measured the hard way: the first run of this spec died on
    // "Test timeout of 90000ms exceeded while setting up deepPagedExperiment".
    test.describe.configure({ timeout: 1_800_000 });

    test(
      'every page of a 5,000-item un-aggregated experiment is an exact, correctly-paired slice',
      {
        tag: [
          '@cap:experiments.compare-deep-paging',
          '@cap:experiments.experiment-item-read',
        ],
      },
      async ({ deepPagedExperiment, backendClient, sdkClient, page }) => {
        const {
          projectId,
          datasetId,
          settledExperimentId,
          freshExperimentId,
          freshWrittenAtMs,
          rawBranchWindowMs,
          itemCount,
          seeded,
        } = deepPagedExperiment;

        const seededByDatasetItemId = new Map(seeded.map((row) => [row.datasetItemId, row]));

        /**
         * The rows whose pairing does not match what the seed wrote for that
         * dataset item id: the experiment read, the trace, and the `idx`.
         *
         * Keyed off the row's OWN id rather than its position, so nothing here
         * depends on the order the endpoint chose — which is what makes it
         * reusable across two walks at different page sizes.
         *
         * `idx` is checked against the seed rather than only for monotonicity
         * because a systematic shift — every row handed the index of its
         * neighbour — is still strictly ordered, still 5,000 distinct ids and
         * still the right total. Only the id → idx binding catches it, and at a
         * page boundary an off-by-one slice is exactly the shape it takes.
         */
        const pairingBreaks = (
          rows: Awaited<ReturnType<typeof backendClient.compareItemsPairedPage>>['rows'],
        ) =>
          rows
            .map((row) => {
              const want = seededByDatasetItemId.get(row.id);
              return {
                id: row.id,
                idx: row.idx,
                wantIdx: want?.idx ?? null,
                experiments: row.experimentItems.map((ei) => ei.experimentId),
                got: row.experimentItems.map((ei) => ei.traceId),
                want: want?.traceId,
              };
            })
            .filter(
              (row) =>
                row.want === undefined ||
                row.idx !== row.wantIdx ||
                row.experiments.length !== 1 ||
                row.experiments[0] !== freshExperimentId ||
                row.got.length !== 1 ||
                row.got[0] !== row.want,
            );

        /**
         * Walk every page at `size`, plus the page after the last, asserting the
         * shape of each page as it goes. Returns the rows in the order the
         * endpoint handed them over.
         */
        const walk = async (size: number) => {
          const fullPages = Math.floor(itemCount / size);
          const remainder = itemCount % size;
          const expectedLengths = [
            ...Array.from({ length: fullPages }, () => size),
            ...(remainder > 0 ? [remainder] : []),
          ];

          const rows: Awaited<
            ReturnType<typeof backendClient.compareItemsPairedPage>
          >['rows'] = [];

          for (const [i, expectedLength] of expectedLengths.entries()) {
            const pageNumber = i + 1;
            const answer = await backendClient.compareItemsPairedPage({
              datasetId,
              experimentIds: [freshExperimentId],
              page: pageNumber,
              size,
            });
            // The total on EVERY page, not just the first: the count and the
            // rows are two different projections of the same query, and a
            // pruning bug that dropped rows from a page can leave the envelope's
            // total intact — or the reverse.
            expect(answer.total, `total on page ${pageNumber} of the size-${size} walk`).toBe(
              itemCount,
            );
            expect(
              answer.rows.length,
              `rows on page ${pageNumber} of the size-${size} walk`,
            ).toBe(expectedLength);
            rows.push(...answer.rows);
          }

          // The page after the last must be EMPTY, not short. A reader that
          // clamped the offset instead of running off the end would re-serve the
          // last slice here, which is how a client that pages until it sees a
          // short page loops forever.
          const pastEnd = await backendClient.compareItemsPairedPage({
            datasetId,
            experimentIds: [freshExperimentId],
            page: expectedLengths.length + 1,
            size,
          });
          expect(
            pastEnd.total,
            `total on the page after the last of the size-${size} walk`,
          ).toBe(itemCount);
          expect(
            pastEnd.rows,
            `the page after the last of the size-${size} walk must be empty, not a repeat`,
          ).toEqual([]);

          return rows;
        };

        const defaultWalk = await test.step(
          `Page the un-aggregated experiment at the shipped default size (${DEFAULT_PAGE_SIZE})`,
          () => walk(DEFAULT_PAGE_SIZE),
        );

        await test.step('The pages cover every seeded item exactly once', async () => {
          expect(defaultWalk.length, 'rows across every page').toBe(itemCount);

          const ids = defaultWalk.map((row) => row.id);
          // Stated three ways on purpose, because they fail differently: the set
          // comparison names WHICH ids were dropped or invented, while the
          // distinct count catches an id served on two pages — a duplicate plus
          // a drop leaves the sets equal.
          expect(new Set(ids).size, 'distinct ids across every page').toBe(itemCount);
          expect(
            [...new Set(ids)].sort(),
            'the union of the pages is exactly the seeded dataset items',
          ).toEqual([...seeded.map((row) => row.datasetItemId)].sort());
        });

        await test.step('The slices are contiguous and in order', async () => {
          // Every row must carry its index. `idx` is nullable because a row whose
          // `data` came back empty or malformed is exactly the corruption this
          // spec is looking for, so it is asserted away here rather than coded
          // around below.
          expect(
            defaultWalk.filter((row) => row.idx === null).length,
            'rows that came back without the idx their dataset item was seeded with',
          ).toBe(0);

          // Monotonic, in whichever direction the endpoint chose. Deliberately
          // not "descending", even though `ORDER BY u.id DESC` is what the query
          // does today: the direction is the backend's to change, while
          // "consecutive pages are consecutive slices of one ordering" is the
          // property a pager must have. The fixture mints dataset-item ids at
          // strictly increasing milliseconds so that id order and idx order
          // agree, which is what makes this readable as an idx sequence at all.
          //
          // Ordering ALONE would accept a systematic shift, so this check is
          // only half the claim — `pairingBreaks` below pins each row's idx to
          // its own id, and the two together are what say "contiguous".
          const indices = defaultWalk.map((row) => row.idx as number);
          const descending = indices[0] > indices[indices.length - 1];
          // Reported with their position and the value before them, not as bare
          // numbers: the useful thing about a break in this sequence is WHERE it
          // is, because `position` divided by the page size names the page
          // boundary the rows crossed when they went wrong.
          const breaks = indices.flatMap((value, i) => {
            if (i === 0) return [];
            const ordered = descending ? value < indices[i - 1] : value > indices[i - 1];
            return ordered ? [] : [{ position: i, previous: indices[i - 1], value }];
          });
          expect(
            breaks,
            `idx sequence across the pages must be strictly ${descending ? 'descending' : 'ascending'}; ` +
              'an interleaved or overlapping page boundary shows up here and nowhere else',
          ).toEqual([]);
        });

        await test.step('Each row carries the idx and the trace its own dataset item was paired with', async () => {
          // The half the two checks above cannot see. Completeness and
          // uniqueness are both satisfied by ANY permutation of the pairing: a
          // read that gave row N the trace seeded for row M still returns 5,000
          // distinct ids, the right total and the right page lengths. The join
          // between an experiment item, its dataset item and its trace is
          // precisely what a paged assembly can scramble.
          expect(
            pairingBreaks(defaultWalk),
            'rows whose idx, experiment or trace is not the one seeded for that dataset item',
          ).toEqual([]);
        });

        await test.step(
          `The same walk at size ${ALTERNATE_PAGE_SIZE} returns the identical sequence`,
          async () => {
            const alternateWalk = await walk(ALTERNATE_PAGE_SIZE);
            // Ids in order, not sorted: a boundary bug that dropped or repeated a
            // row only at one particular page size is invisible to a single walk,
            // and comparing sorted ids would hide a reordering too.
            expect(
              alternateWalk.map((row) => row.id),
              `paging at ${ALTERNATE_PAGE_SIZE} must yield the same rows in the same order as at ${DEFAULT_PAGE_SIZE}`,
            ).toEqual(defaultWalk.map((row) => row.id));
            // Against the SEED, not against the other walk: the two walks cut
            // the same ordering at different offsets, so the whole reason to
            // read twice is that a pairing this size's boundaries scramble
            // would be one the other walk never had a chance to get wrong.
            // Comparing the walks to each other would only say they agree.
            expect(
              pairingBreaks(alternateWalk),
              `rows the size-${ALTERNATE_PAGE_SIZE} walk paired with an idx, experiment or trace other than the seeded one`,
            ).toEqual([]);
          },
        );

        const inWindowFinishedAtMs = Date.now();

        await test.step('Those reads were taken while the experiment was still un-aggregated', () => {
          // The premise of everything above, asserted rather than assumed. The
          // denormalization debounce is 1m from the last write; if the reads
          // overran that, they were served by the aggregated branch and this test
          // has NOT exercised the branch OPIK-8274 rewrote. Failing here says
          // "could not pin the branch", which is the honest outcome — a pass
          // would be coverage of something else under this spec's name.
          const elapsedMs = inWindowFinishedAtMs - freshWrittenAtMs;
          expect(
            elapsedMs,
            `the paged reads finished ${elapsedMs}ms after the last experiment-item write; past ` +
              `${rawBranchWindowMs}ms the denormalization job may have run, so push_top_limit_raw ` +
              'is no longer guaranteed to be the branch under test',
          ).toBeLessThan(rawBranchWindowMs);
        });

        await test.step('The compare grid agrees with the API at the first and last offset', async () => {
          // Both experiments, which is the real comparison view and the shape the
          // release report names. No branch claim here either: with one
          // aggregated and one raw experiment in scope the read takes neither
          // push-down, and the property under test — the grid's offset
          // arithmetic and its slice — holds on every branch.
          const experimentIds = [settledExperimentId, freshExperimentId];
          const compare = new CompareExperimentsPage(page, projectId, datasetId, experimentIds);
          const lastPage = itemCount / GRID_PAGE_SIZE;
          expect(
            lastPage,
            'the grid page size must divide the seed, or the footer arithmetic below is wrong',
          ).toBe(Math.floor(lastPage));

          const total = itemCount.toLocaleString('en-US');

          /** The grid's slice must be the API's slice at the same offset, in order. */
          const expectMatchesApi = async (
            grid: { total: number; ids: string[] },
            pageNumber: number,
          ) => {
            expect(grid.total, `the grid's total at page ${pageNumber}`).toBe(itemCount);
            expect(grid.ids, `rows the grid read at page ${pageNumber}`).toHaveLength(
              GRID_PAGE_SIZE,
            );
            const direct = await backendClient.compareItemsPairedPage({
              datasetId,
              experimentIds,
              page: pageNumber,
              size: GRID_PAGE_SIZE,
            });
            expect(
              grid.ids,
              `the grid's page ${pageNumber} must be the same slice, in the same order, as a direct API read at that offset`,
            ).toEqual(direct.rows.map((row) => row.id));
          };

          const first = await compare.gotoResultsPage(1, GRID_PAGE_SIZE);
          await compare.waitForResultsReady();
          await compare.expectPaginationFooter(`Showing 1-${GRID_PAGE_SIZE} of ${total}`);
          await expectMatchesApi(first, 1);

          // Clicked, not navigated — see `clickLastResultsPage`. This is also the
          // only offset in the spec that the front end computes for itself
          // (`pageChange(totalPages)`), so it is where a total the grid got wrong
          // turns into a page a user cannot reach.
          const last = await compare.clickLastResultsPage(lastPage, GRID_PAGE_SIZE);
          await compare.expectPaginationFooter(
            `Showing ${itemCount - GRID_PAGE_SIZE + 1}-${itemCount} of ${total}`,
          );
          await expectMatchesApi(last, lastPage);
        });

        await test.step(
          'The Python SDK reads the whole experiment at its own default page size',
          async () => {
            // `Experiment.get_items()` pages this endpoint with no filters, no
            // search and no sorting, at a default page size of 2,000 — so a
            // 5,000-item experiment is the first time the estate makes it page at
            // the shipped default. No branch claim: this runs after the window
            // above, and the wave loop it exercises is the same code whichever
            // branch serves the pages.
            const read = await sdkClient.python.readExperimentItems({
              experiment_id: freshExperimentId,
            });

            expect(read.count, 'the default SDK read returns every seeded item').toBe(itemCount);
            expect(read.items, 'the default SDK read returns every seeded item').toHaveLength(
              itemCount,
            );
            expect(
              read.items.filter((item) => item.idx === null).length,
              'items that came back without their seeded idx',
            ).toBe(0);
            expect(
              [...read.items.map((item) => item.idx)].sort((a, b) => a! - b!),
              'the default SDK read covers idx 0..n-1 exactly once each',
            ).toEqual(Array.from({ length: itemCount }, (_, i) => i));
            // Sorted by idx and compared whole, so a failure names the indices
            // that moved rather than only saying a set differed. The endpoint
            // orders by its own key rather than by the seeded idx, so absolute
            // order is not the SDK's to promise — the pairing is.
            expect(
              [...read.items]
                .sort((a, b) => a.idx! - b.idx!)
                .map((item) => ({
                  idx: item.idx,
                  datasetItemId: item.dataset_item_id,
                  traceId: item.trace_id,
                })),
              'each SDK-read item pairs the dataset item and the trace its idx was seeded with',
            ).toEqual(seeded);
          },
        );
      },
    );
  },
);
