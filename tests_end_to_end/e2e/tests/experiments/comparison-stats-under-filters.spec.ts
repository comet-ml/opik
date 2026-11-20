import { test, expect, CSF_SCORE_NAME } from '@e2e/fixtures';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';
import { displayedDuration } from '@e2e/core/duration';
import type { BackendFilter, ProjectStatRef } from '@e2e/core/backend';

/**
 * The column STATISTICS the experiment-comparison Results tab answers with
 * under each kind of filter it can apply — the read opik#9618 (OPIK-6915)
 * moved from an INNER JOIN `ON` to a `WHERE`, and the one no spec in the
 * estate looks at.
 *
 * ## The gap this closes
 *
 * `compare-export-all-rows.spec.ts` applies a `bucket (Dataset)` filter to this
 * very grid, and `CompareExperimentsPage.addGridFilter` says in its own comment
 * that it waits only for `/items/experiments/items` — "not one of the sibling
 * column/statistics calls that carry the same `filters` param". That is the
 * right settle for a spec about rows, and it is exactly why a statistics call
 * that answered 500, or answered with an empty list, left the whole suite
 * green. `applyGridFilter` waits for both and hands the stats body back, so the
 * row answer and the statistics answer can be held against each other.
 *
 * ## What makes the numbers falsifiable
 *
 * The fixture fixes the three trace durations at 500 / 1000 / 1500 ms, so each
 * filter's expected `duration` p50 is an exact number rather than "some value":
 * one matching row reports its own duration, and the two-row case reports 1250,
 * the interpolation ClickHouse's `quantiles(0.5)` produces. A seed whose rows
 * shared a duration would report the same p50 for every filter, so a stats query
 * that ignored the filter entirely would pass.
 *
 * Each case also pins the row SET, not just the count — the dataset-item ids the
 * grid read returned, compared as a set. A filter that let an extra row through
 * and dropped another keeps the count and is the defect a count alone cannot see.
 *
 * ## What this spec does NOT claim
 *
 * It does not claim the deployment runs the Distributed/cut-over topology the
 * 9618 regression needs. Those flags are not client-readable, and on a
 * pre-cutover deployment the bug does not reproduce at all — so the decisive
 * guard for the fix itself stays `ExperimentComparisonDistributedWrapReadTest`
 * in the Java suite. What this does guard, on every topology, is that the
 * statistics read answers at all, answers for each filter shape, and agrees with
 * the rows the same filter selected.
 */

/** One filter the Results tab can apply, with the whole answer it should produce. */
interface FilterCase {
  /** What a reader should call this case. */
  title: string;
  /** The option label the filter popover's column list renders. */
  column: string;
  /** The filter field as it travels on the wire. */
  field: string;
  /** The wire `type`; ignored by the backend for a known field, required for a dynamic one. */
  type: string;
  /** The operator's dropdown label. */
  operator: string;
  /** Its wire spelling, when the label differs (`is empty` travels as `is_empty`). */
  wireOperator?: string;
  /** The dictionary key — a score name, for a `Feedback scores` filter. */
  key?: string;
  /** Absent for a no-value operator. */
  value?: string;
  /** Indices into `CSF_ROWS` the filter must select, and nothing else. */
  matches: number[];
  /** The `duration` p50 the statistics must report, in ms. */
  durationP50Ms: number;
  /**
   * The `feedback_scores.<name>` average the statistics must report, or `null`
   * when the answer must carry no such stat AT ALL.
   *
   * `null` rather than 0 on purpose, and it is the half of this spec that is
   * about the filter rather than about the quantile: a row set in which nothing
   * is scored has no score to average, so the key is absent from the map the
   * endpoint builds. A build that reported 0 there would be claiming a score
   * nobody gave, and 0 is also a score a row can legitimately hold — which is
   * why one of the cases below expects exactly that.
   */
  scoreAvg: number | null;
}

const FILTER_CASES: readonly FilterCase[] = [
  {
    title: 'a known trace field — Evaluation task contains WRONG',
    column: 'Evaluation task',
    field: 'output',
    type: 'string',
    operator: 'contains',
    value: 'WRONG',
    matches: [2],
    durationP50Ms: 1_500,
    // The matching row is the unscored one, so there is no score to average.
    scoreAvg: null,
  },
  {
    title: 'a dynamic output field — tier (Output) contains gold',
    column: 'tier (Output)',
    field: 'output.tier',
    type: 'string',
    operator: 'contains',
    value: 'gold',
    matches: [0],
    durationP50Ms: 500,
    scoreAvg: 1,
  },
  {
    title: 'a dynamic dataset field — bucket (Dataset) contains beta',
    column: 'bucket (Dataset)',
    field: 'data.bucket',
    type: 'string',
    operator: 'contains',
    value: 'beta',
    matches: [1, 2],
    // Two rows, so the p50 is the interpolation between them rather than a
    // row's own duration — the one case in this table where a quantile bug and
    // a filter bug are distinguishable.
    durationP50Ms: 1_250,
    // One of the two matching rows is scored, and it is scored ZERO: the stat
    // must be present and read 0, which is what separates "no score" from "a
    // score of nothing".
    scoreAvg: 0,
  },
  {
    title: 'a feedback score threshold — Feedback scores >= 0.5',
    column: 'Feedback scores',
    field: 'feedback_scores',
    type: 'feedback_scores_number',
    operator: '>=',
    key: CSF_SCORE_NAME,
    value: '0.5',
    matches: [0],
    durationP50Ms: 500,
    scoreAvg: 1,
  },
  {
    title: 'a feedback score emptiness — Feedback scores is empty',
    column: 'Feedback scores',
    field: 'feedback_scores',
    type: 'feedback_scores_number',
    operator: 'is empty',
    wireOperator: 'is_empty',
    key: CSF_SCORE_NAME,
    matches: [2],
    durationP50Ms: 1_500,
    scoreAvg: null,
  },
];

/** The case as a wire filter, for the API-level read. */
const wireFilter = (filterCase: FilterCase): BackendFilter => ({
  field: filterCase.field,
  type: filterCase.type,
  operator: filterCase.wireOperator ?? filterCase.operator,
  // `value` is required by `ExperimentsComparisonFilter`'s constructor even for
  // a no-value operator, and the front end sends "" there too.
  value: filterCase.value ?? '',
  ...(filterCase.key === undefined ? {} : { key: filterCase.key }),
});

/**
 * One stat by name and type, or a throw naming it.
 *
 * By NAME AND TYPE because `mapExperimentItemsStats` can emit two items under
 * one name — `feedback_scores.<name>` arrives as both an `AVG` and, from
 * `addFeedbackScoresPercentiles`, a `PERCENTAGE`. A lookup on the name alone
 * would read whichever came first and compare an average against a percentile
 * object.
 *
 * Throws on absence rather than returning `undefined`, for the reason
 * `numericStat` gives: a missing aggregate is a regression, not a reason to
 * skip a comparison.
 */
const stat = (stats: ProjectStatRef[], name: string, type: string): unknown => {
  const found = stats.filter((item) => item.name === name && item.type === type);
  if (found.length !== 1) {
    throw new Error(
      `expected exactly one ${type} stat named "${name}", got ${found.length}. ` +
        `The answer carried: ${stats.map((s) => `${s.name}:${s.type}`).join(', ')}`,
    );
  }
  return found[0].value;
};

/** `true` when the answer carries no stat of any type under `name`. */
const hasStat = (stats: ProjectStatRef[], name: string): boolean =>
  stats.some((item) => item.name === name);

/** The `feedback_scores.<name>` stat the endpoint builds from the score map. */
const SCORE_STAT_NAME = `feedback_scores.${CSF_SCORE_NAME}`;

test.describe(
  'Experiment comparison — column statistics under each kind of filter',
  { tag: ['@area:experiments'] },
  () => {
    test(
      'Every filter kind answers statistics that match the rows it selected',
      { tag: ['@t2-cuj', '@cap:experiments.compare-filtered-statistics'] },
      async ({ comparisonStatsFilters, backendClient }) => {
        const seed = comparisonStatsFilters;
        const experimentIds = [seed.experimentId];

        for (const filterCase of FILTER_CASES) {
          await test.step(`Statistics for ${filterCase.title}`, async () => {
            const filters = [wireFilter(filterCase)];
            const expectedIds = filterCase.matches.map((i) => seed.rows[i].datasetItemId);

            const rows = await backendClient.compareItemsPage({
              datasetId: seed.datasetId,
              experimentIds,
              filters,
            });
            // The whole answer, not a membership test: a filter that also let a
            // row through it should have excluded keeps every "my row is here"
            // check happy, and is the defect this endpoint's filters exist to
            // avoid.
            expect(rows.total, 'the filtered row read reports the matching count').toBe(
              expectedIds.length,
            );
            expect(
              [...rows.ids].sort(),
              'and returns exactly the matching rows',
            ).toEqual([...expectedIds].sort());

            const answer = await backendClient.compareItemsStats({
              datasetId: seed.datasetId,
              experimentIds,
              filters,
            });
            expect(
              answer.status,
              `the statistics read answered: ${answer.message}`,
            ).toBe(200);
            expect(
              answer.stats.length,
              'with a non-empty statistics list — a blank row is the regression shape',
            ).toBeGreaterThan(0);

            // The two halves held against each other. This is the comparison
            // the UI cannot make for itself: the grid renders the rows from one
            // request and the header numbers from another, so a reader sees
            // "2 rows" above a statistic computed over five.
            expect(
              stat(answer.stats, 'experiment_items_count', 'COUNT'),
              'the statistics count the same experiment items the rows did',
            ).toBe(expectedIds.length);
            expect(
              stat(answer.stats, 'trace_count', 'COUNT'),
              'and the same traces',
            ).toBe(expectedIds.length);

            expect(
              stat(answer.stats, 'duration', 'PERCENTAGE'),
              'and report the p50 of exactly those rows\' seeded durations',
            ).toMatchObject({ p50: filterCase.durationP50Ms });

            if (filterCase.scoreAvg === null) {
              expect(
                hasStat(answer.stats, SCORE_STAT_NAME),
                'a row set with nothing scored carries no score statistic at all',
              ).toBe(false);
            } else {
              expect(
                stat(answer.stats, SCORE_STAT_NAME, 'AVG'),
                'the score average over exactly the matching rows',
              ).toBe(filterCase.scoreAvg);
            }
          });
        }

        await test.step('All of the above was read before the experiment could aggregate', async () => {
          // The premise, asserted last because it is about the elapsed time of
          // the reads above. `experimentDenormalization.debounceDelay` is 1m
          // from the last write; past it the denormalization job may have run
          // and the statistics query takes its aggregated branch instead of the
          // `experiment_items` -> `traces` one 9618 is about. Same check, and
          // the same window, as `compare-raw-sorted-paging.spec.ts`.
          const elapsed = Date.now() - seed.writtenAtMs;
          expect(
            elapsed,
            `the reads took ${elapsed}ms; past ${seed.rawBranchWindowMs}ms the ` +
              'denormalization job may have run, so the un-aggregated branch is no ' +
              'longer the one that answered',
          ).toBeLessThan(seed.rawBranchWindowMs);
        });
      },
    );

    test(
      'The grid renders the statistics its own filtered read answered with',
      { tag: ['@t2-cuj', '@cap:experiments.compare-filtered-statistics'] },
      async ({ comparisonStatsFilters, page }) => {
        // Five filters through the real popover, each a page round trip on a
        // cold compare view. Well past the 90s default, and the seeding is
        // inside the budget too.
        test.setTimeout(300_000);

        const seed = comparisonStatsFilters;
        const compare = new CompareExperimentsPage(page, seed.projectId, seed.datasetId, [
          seed.experimentId,
        ]);

        await test.step('Open the comparison Results tab', async () => {
          await compare.gotoResults();
          await compare.waitForResultsReady();
          await compare.expectRenderedRowCount(seed.rows.length);
        });

        for (const filterCase of FILTER_CASES) {
          await test.step(`The grid under ${filterCase.title}`, async () => {
            const applied = await compare.applyGridFilter({
              column: filterCase.column,
              field: filterCase.field,
              operator: filterCase.operator,
              wireOperator: filterCase.wireOperator,
              key: filterCase.key,
              value: filterCase.value,
            });

            expect(
              applied.total,
              'the grid received the matching row count',
            ).toBe(filterCase.matches.length);
            await compare.expectRenderedRowCount(filterCase.matches.length);

            // The statistics the page itself was handed, before reading what it
            // rendered from them. Asserting only the rendered text would pass
            // against a page that formatted a wrong number correctly, and
            // asserting only the payload is what the API test above already does.
            expect(
              stat(applied.stats, 'experiment_items_count', 'COUNT'),
              'the statistics the page received count the rows it is showing',
            ).toBe(filterCase.matches.length);

            await expect(
              compare.columnStatistic('duration'),
              'the Duration header shows the p50 of the filtered rows',
            ).toHaveText(`p50${displayedDuration(filterCase.durationP50Ms)}`);

            if (filterCase.scoreAvg === null) {
              // No stat at all for the score, so `HeaderStatistic` falls through
              // to its own dash. The dash is the claim here: a column that had
              // kept the PREVIOUS filter's number would look entirely ordinary.
              await expect(
                compare.columnStatistic(`feedback_scores_${CSF_SCORE_NAME}`),
                'and the score column reports nothing, rather than a stale number',
              ).toHaveText('-');
            } else {
              await expect(
                compare.columnStatistic(`feedback_scores_${CSF_SCORE_NAME}`),
                'and the score column shows the average over the filtered rows',
              ).toHaveText(`avg${filterCase.scoreAvg}`);
            }
          });
        }
      },
    );
  },
);
