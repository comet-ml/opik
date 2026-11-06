import { test, expect } from '@e2e/fixtures';
import {
  SETTLED_ITEM_COUNT,
  compareMarker,
} from '@e2e/fixtures/settled-compare-experiments.fixture';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * A default-sorted compare page pairs every payload with the row and the
 * experiment it belongs to, on an experiment the denormalization job has had
 * time to settle (opik#9402, OPIK-8274).
 *
 * 9402 stopped carrying `input`, `output` and `trace_metadata` through the
 * aggregation and joins them once per page instead (`late_materialize`, and
 * `late_materialize_raw` on the un-aggregated branch). The whole class of
 * failure that introduces is a MIS-JOIN, and a mis-join is silent: the page
 * comes back with the right row ids, the right `total`, the right number of
 * bands per row — and another row's input, output and metadata inside them.
 * Every count-based check passes. The compare Results tab is a page people read
 * daily, and what they would see is one experiment's answer attributed to
 * another.
 *
 * Why the existing specs do not cover this:
 *
 *  - `compare-raw-sorted-paging.spec.ts` and `compare-deep-paging.spec.ts` both
 *    name this ticket and both pin the RAW branch — deliberately, and each
 *    asserts that its reads finished inside the window where the experiment is
 *    guaranteed un-aggregated. Neither reads a payload: they pin ids, totals,
 *    ordering and the row→trace pairing.
 *  - `experiments-compare.spec.ts` does read per-item output on a default,
 *    unsorted, unfiltered compare read — which is the late-materialized shape —
 *    but over a fixture whose experiments may or may not have denormalized by
 *    read time, and it checks one item at a time rather than the whole page.
 *
 * So the aggregated branch is only incidentally covered, and it is the one every
 * real user hits once the job has run.
 *
 * ## What this spec's premise is, exactly
 *
 * `settledCompareExperiments` waits 100 seconds with no writes after linking
 * the experiments, which covers the 1-minute debounce, the 5-second flush scan
 * and the aggregate write. It cannot assert that the aggregated branch served
 * the reads below, because **no API response says which branch served a read** —
 * `GET /experiments/{id}`, the experiments listing and the compare read itself
 * are byte-identical before and after aggregation. On a deployment whose
 * denormalization job is off or wedged, this re-exercises the raw branch. The
 * elapsed quiet period is asserted, as the strongest premise available; the
 * join assertion is worth making either way, and the fixture header says the
 * same thing in the same words.
 *
 * Both surfaces, because the join is where they can disagree: the read is
 * asserted through the compare API at three page sizes, cross-checked through
 * `Experiment.get_items()` — a different SDK path over the same pairing — and
 * then read off the rendered Results grid, where a user would actually meet the
 * defect.
 *
 * Deterministic and free: 30 items, two experiments, every trace payload
 * carrying its own `(experiment, idx)` marker. No model, no scores, no clock
 * arithmetic beyond the settle wait.
 *
 * `@t3-nightly` and not `@t2-cuj`: the mandatory 100-second quiet period is
 * dead time no amount of tuning removes, which is the wrong trade for a
 * per-merge gate. `experiments.compare-side-by-side` already reads `t2` from a
 * sibling spec, so the tier this records is not the key's.
 */

/**
 * The page sizes the paged walk uses.
 *
 * 7 and 25 both divide 30 unevenly, so each walk crosses boundaries at offsets
 * that divide neither the total nor the page size, and each ends on a short
 * page. 30 takes the whole comparison in one read, which is the baseline the
 * other two are compared against — a join that only breaks when a page is a
 * strict subset would otherwise look fine.
 */
const PAGE_SIZES = [7, 25, SETTLED_ITEM_COUNT] as const;

/**
 * The grid page size the UI half reads at.
 *
 * 12 into 30 gives two full pages and a short one of 6, so the first page and
 * the last page are the two slice shapes — and both fit a 1000px-tall viewport,
 * which matters because the grid is virtualised and only renders what is in it.
 */
const UI_PAGE_SIZE = 12;

test.describe(
  'Experiments comparison — payload join on a settled experiment (opik#9402)',
  { tag: ['@t3-nightly', '@area:experiments'] },
  () => {
    // The compare grid pins its first column and splits every cell into one
    // band per experiment; below ~1600px the output column is the first to go.
    test.use({ viewport: { width: 1600, height: 1000 } });

    // `describe.configure` and not `test.setTimeout`, which is called from the
    // test body — by then the fixture has already spent its 100-second quiet
    // period plus the seed against the 90s default, so the run dies in setup
    // and the body never gets to raise the limit.
    test.describe.configure({ timeout: 900_000 });

    test(
      'every payload on every page belongs to that row and that experiment, and the grid renders it',
      { tag: ['@cap:experiments.compare-side-by-side'] },
      async ({ settledCompareExperiments, sdkClient, backendClient, project, page }) => {
        const seed = settledCompareExperiments;
        const experimentIds = seed.experiments.map((e) => e.id);
        const expectedItemIds = [...seed.datasetItemIds].sort();
        /** `experimentId` -> the number its payloads self-report. */
        const numberByExperimentId = new Map(seed.experiments.map((e) => [e.id, e.number]));

        await test.step('The comparison was left quiet long enough for the job to run', () => {
          // The premise, asserted rather than assumed — see the header for what
          // it does and does not establish. Failing here means the fixture did
          // not deliver its own quiet period, which would make every
          // "aggregated branch" claim below unfounded.
          expect(
            seed.settledForMs,
            'the fixture must have waited out the denormalization debounce and a flush scan',
          ).toBeGreaterThanOrEqual(90_000);
        });

        for (const size of PAGE_SIZES) {
          await test.step(`At page size ${size}, every row's payloads are its own`, async () => {
            const pageCount = Math.ceil(SETTLED_ITEM_COUNT / size);
            const seenItemIds: string[] = [];

            for (let pageNumber = 1; pageNumber <= pageCount; pageNumber += 1) {
              const answer = await backendClient.compareItemsTraceData({
                datasetId: seed.datasetId,
                experimentIds,
                page: pageNumber,
                size,
              });
              // The total on EVERY page, not only the first: the count and the
              // rows are two projections of one query, and a slice bug can
              // move one without the other.
              expect(
                answer.total,
                `total on page ${pageNumber} of the size-${size} walk`,
              ).toBe(SETTLED_ITEM_COUNT);
              const expectedLength = Math.min(size, SETTLED_ITEM_COUNT - (pageNumber - 1) * size);
              expect(
                answer.rows.length,
                `rows on page ${pageNumber} of the size-${size} walk`,
              ).toBe(expectedLength);

              for (const row of answer.rows) {
                seenItemIds.push(row.id);
                // The row's own index, taken from the response's `data` rather
                // than from the fixture's map, and then cross-checked against
                // it. Both are needed: `data` is what the page says the row is,
                // and the map is what the seed knows it is — a read that
                // scrambled `data` as well as the payloads would otherwise be
                // self-consistent.
                const idxFromResponse = row.data.idx;
                expect(
                  typeof idxFromResponse,
                  `row ${row.id} carries its dataset-item data on page ${pageNumber}`,
                ).toBe('number');
                const idx = idxFromResponse as number;
                expect(
                  idx,
                  `row ${row.id} is the dataset item the seed wrote at index ${idx}`,
                ).toBe(seed.idxByItemId[row.id]);

                // One band per experiment, every time. A row that lost a band
                // has nothing to mis-join and would pass the payload checks
                // below by vacuity.
                expect(
                  [...row.experimentItems.map((ei) => ei.experimentId)].sort(),
                  `row ${row.id} carries one result per experiment`,
                ).toEqual([...experimentIds].sort());

                for (const item of row.experimentItems) {
                  const experimentNumber = numberByExperimentId.get(item.experimentId);
                  expect(
                    experimentNumber,
                    `row ${row.id} names an experiment this comparison asked for`,
                  ).toBeDefined();
                  const marker = compareMarker(experimentNumber as number, idx);
                  const where =
                    `row idx=${idx} / experiment ${experimentNumber} on page ${pageNumber} ` +
                    `of the size-${size} walk`;
                  // All three fields, compared whole. Each is materialized by
                  // the same join, so a mis-join moves them together — but a
                  // join that reached only `output` is a different defect, and
                  // only a per-field assertion separates the two. `toEqual`
                  // rather than a substring, so a payload that merely CONTAINS
                  // the right marker beside another row's data still fails.
                  expect(item.input, `${where}: input`).toEqual({ marker });
                  expect(item.output, `${where}: output`).toEqual({ output: marker });
                  expect(item.traceMetadata, `${where}: metadata`).toEqual({ marker });
                }
              }
            }

            // The walk is an exact partition: every seeded item once, no
            // duplicates, nothing extra. Asserted per page size because the
            // pages are what the join runs once each over.
            expect(
              [...seenItemIds].sort(),
              `the size-${size} walk visits every seeded item exactly once`,
            ).toEqual(expectedItemIds);
          });
        }

        await test.step('Experiment.get_items() agrees on the same pairing', async () => {
          // A different read path over the same join — the SDK's own, which is
          // what a user cross-checking a suspicious compare row would reach
          // for. If the two disagree, one of them is wrong and the compare page
          // alone cannot say which.
          for (const experiment of seed.experiments) {
            const read = await sdkClient.python.readExperimentItems({
              experiment_id: experiment.id,
              max_results: SETTLED_ITEM_COUNT,
            });
            expect(
              read.count,
              `Experiment.get_items() for ${experiment.name} returns every item`,
            ).toBe(SETTLED_ITEM_COUNT);
            expect(
              [...read.items.map((i) => i.dataset_item_id)].sort(),
              `and they are exactly the seeded dataset items`,
            ).toEqual(expectedItemIds);

            for (const item of read.items) {
              // The index the SDK echoes back off the dataset item, compared
              // against the seed's. Asserted present first: the bridge types
              // it nullable, and a `?? 0` here would make every row agree with
              // item 0.
              expect(
                item.idx,
                `${experiment.name} item ${item.dataset_item_id} carries its index`,
              ).not.toBeNull();
              expect(
                item.idx,
                `${experiment.name} pairs ${item.dataset_item_id} with its own index`,
              ).toBe(seed.idxByItemId[item.dataset_item_id]);
            }
          }
        });

        const compare = new CompareExperimentsPage(
          page,
          project.id,
          seed.datasetId,
          experimentIds,
        );

        await test.step('The Results grid renders each row’s own two bands', async () => {
          /**
           * Every row id this step actually read bands for, across both pages.
           *
           * Accumulated rather than counted, because the grid is VIRTUALISED:
           * only the rows in the viewport carry a `data-row-id`, so the
           * rendered set has to be read instead of assumed, and the
           * no-row-on-two-pages check below has to know what was looked at.
           */
          const renderedAcrossPages: string[] = [];

          /** Read and assert every addressable row on whichever page is up. */
          const assertRenderedPage = async (label: string): Promise<void> => {
            await compare.waitForResultsReady();
            const rendered = await compare.itemRowOrder();
            expect(
              rendered.length,
              `${label} rendered at least one addressable row`,
            ).toBeGreaterThan(0);

            for (const itemId of rendered) {
              const idx = seed.idxByItemId[itemId];
              expect(idx, `${label} rendered a row this test seeded (${itemId})`).toBeDefined();
              renderedAcrossPages.push(itemId);

              for (const [bandIndex, experiment] of seed.experiments.entries()) {
                // The band index IS the position of the experiment id in the
                // array the POM was constructed with, which is the order the
                // bands render in — so this also asserts the grid did not swap
                // the two experiments' columns, which a per-row payload check
                // alone would not notice.
                expect(
                  await compare.readItemOutput(itemId, bandIndex),
                  `${label}: band ${bandIndex} for row idx=${idx} must show ` +
                    `experiment ${experiment.number}'s own output for that row`,
                ).toBe(compareMarker(experiment.number, idx));
              }
            }
          };

          // Page 1 by URL. `gotoResultsPage` is only safe for page 1 on a cold
          // load — `DataTablePagination` sends a deep offset back to page 1
          // while `total` is still 0 — which its own docblock says.
          const firstPage = await compare.gotoResultsPage(1, UI_PAGE_SIZE);
          expect(firstPage.total, 'the grid received the whole comparison').toBe(
            SETTLED_ITEM_COUNT,
          );
          await assertRenderedPage(`page 1 at size ${UI_PAGE_SIZE}`);

          // Then the LAST page, by the control, which is the only way a user
          // reaches a deep offset with `total` already known. Two pages and not
          // three: the compare POM offers a jump-to-last but no next-page
          // walker, and the last page here is a SHORT one (30 at 12 leaves 6),
          // which with the full first page covers both slice shapes. The
          // middle page is read by the API walk above at three page sizes.
          const pageCount = Math.ceil(SETTLED_ITEM_COUNT / UI_PAGE_SIZE);
          const lastPage = await compare.clickLastResultsPage(pageCount, UI_PAGE_SIZE);
          expect(
            lastPage.total,
            'the grid still reports the whole comparison on its last page',
          ).toBe(SETTLED_ITEM_COUNT);
          expect(
            lastPage.ids.length,
            `the last page is the short one (${SETTLED_ITEM_COUNT} at ${UI_PAGE_SIZE})`,
          ).toBe(SETTLED_ITEM_COUNT - (pageCount - 1) * UI_PAGE_SIZE);
          await assertRenderedPage(`page ${pageCount} at size ${UI_PAGE_SIZE}`);

          // No row was rendered on two different pages. A grid that re-served a
          // slice shows every band correctly and still lies about the
          // comparison, which no per-row assertion above can see.
          expect(
            new Set(renderedAcrossPages).size,
            'no dataset item was rendered on more than one page of the grid',
          ).toBe(renderedAcrossPages.length);
        });
      },
    );
  },
);
