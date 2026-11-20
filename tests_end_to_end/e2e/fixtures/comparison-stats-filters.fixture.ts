import { test as baseTest, expect } from './dataset-column-mapping.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * The feedback-score definition the two scored rows carry, and the one the
 * `>=` and `is empty` filters name.
 *
 * One name across both, not two: `is empty` asks whether THIS score is absent
 * on a row, so a second definition would make an unscored row match for the
 * wrong reason.
 */
export const CSF_SCORE_NAME = 'csf_metric';

/**
 * The three rows, as the seed decides them — durations, payloads and score in
 * one table so the expected answer for each filter is readable beside it.
 *
 * Every number here is load-bearing:
 *
 *  - **The durations are 500 / 1000 / 1500 ms and distinct.** The statistics
 *    answer is a `duration` p50, and ClickHouse `quantiles(0.5)` interpolates:
 *    over one row it is that row's duration, over rows 2 and 3 it is 1250.
 *    Distinct durations are what make the p50 a falsifiable NUMBER — a seed
 *    whose rows shared a duration would report the same p50 for every filter,
 *    so a stats query that ignored the filter entirely would pass.
 *  - **`tier` and the answer text pick out exactly one row each.** `gold`
 *    appears on row 0 only; `WRONG` appears in row 2's output only. Asserted
 *    by the fixture, below, rather than assumed from reading this table.
 *  - **`bucket` is alpha / beta / beta.** Two rows, so the dataset-column
 *    filter is the one case where the p50 is an interpolation rather than a
 *    row's own duration — the shape a quantile bug shows up in.
 *  - **Row 2 is left UNSCORED.** `is empty` needs a target, and it has to be
 *    the row with the distinctive duration so that a stats answer computed
 *    over the wrong row set reports the wrong p50 rather than the right one by
 *    coincidence.
 */
interface ComparisonStatsRow {
  /** `data.bucket` on the dataset item — the dynamic DATASET column filtered on. */
  bucket: string;
  /** `output.tier` on the trace — a dynamic OUTPUT column. */
  tier: string;
  /** `output.answer` on the trace — reached through the `Evaluation task` filter. */
  answer: string;
  durationMs: number;
  /** `null` leaves the row unscored, which is what `is empty` selects. */
  score: number | null;
}

export const CSF_ROWS: readonly ComparisonStatsRow[] = [
  { bucket: 'alpha', tier: 'gold', answer: 'csf-answer-right', durationMs: 500, score: 1 },
  { bucket: 'beta', tier: 'silver', answer: 'csf-answer-close', durationMs: 1_000, score: 0 },
  { bucket: 'beta', tier: 'bronze', answer: 'WRONG', durationMs: 1_500, score: null },
];

/** One seeded row, with the ids the spec addresses it by. */
export interface ComparisonStatsRowRef extends ComparisonStatsRow {
  datasetItemId: string;
  traceId: string;
  /** Position in `CSF_ROWS`, carried on the dataset item as `data.idx`. */
  idx: number;
}

export interface ComparisonStatsFiltersRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  /** The three rows, in seed order. */
  rows: ComparisonStatsRowRef[];
  scoreName: string;
  /** When the experiment items were written — the clock the raw window runs from. */
  writtenAtMs: number;
  /** How long after that write the experiment is still guaranteed un-aggregated. */
  rawBranchWindowMs: number;
}

export interface ComparisonStatsFiltersFixtures {
  comparisonStatsFilters: ComparisonStatsFiltersRef;
}

/**
 * `experimentDenormalization.debounceDelay` is 1m and every write to the
 * experiment resets it. Two thirds of that is the margin this fixture
 * promises, the same number and for the same reason as
 * `raw-branch-experiment.fixture.ts`: long enough for a handful of small API
 * reads, short enough that a slow run fails the premise rather than quietly
 * reading the aggregated branch.
 */
const RAW_BRANCH_WINDOW_MS = 40_000;

const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 1_000;

/**
 * Three dataset items and one experiment over them, with fixed trace durations,
 * a dynamic dataset column, two dynamic output columns and one row deliberately
 * unscored — the seed for the comparison STATISTICS read under each kind of
 * filter the Results tab can apply (opik#9618 / OPIK-6915).
 *
 * ## Why a new fixture
 *
 * None of the comparison fixtures can answer a statistics question.
 * `comparison` seeds through the SDK bridge's `compareSeed`, so its trace
 * durations are whatever the evaluation task happened to take — and a p50 of
 * "some value" is not an assertion. `settledCompareExperiments` and
 * `rawBranchExperiment` fix no durations either and carry no feedback scores,
 * so neither the `>=` nor the `is empty` filter has a target in them. Every
 * number this seed fixes exists so that the statistics answer is an exact one.
 *
 * ## One experiment, not two
 *
 * The statistics endpoint takes `experiment_ids` and aggregates over the
 * experiment ITEMS it resolves; the filter shapes under test are all about
 * which items survive the filter, not about how two experiments pair up. A
 * second experiment would double every count and make the p50 an average over
 * two sets of durations without adding a filter shape, so there is one.
 *
 * ## What this fixture can promise, and what it cannot
 *
 * It can promise that the experiment was never settled — the items are the last
 * write and nothing waits afterwards — and it reports `writtenAtMs` so a
 * caller can assert that its own read happened inside the debounce window. It
 * CANNOT promise which branch served the read, for exactly the reason
 * `settled-compare-experiments.fixture.ts` documents at length: no field of
 * any response reports the choice. Nor can it promise the Distributed/cut-over
 * topology the 9618 regression needs; that is not client-readable, and the
 * decisive guard for it is `ExperimentComparisonDistributedWrapReadTest` in
 * the Java suite.
 *
 * Teardown deletes the experiment, the dataset and the traces. None of the
 * three cascades with the project.
 */
export const test = baseTest.extend<ComparisonStatsFiltersFixtures>({
  comparisonStatsFilters: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-csf-ds`;
    const experimentName = `${testNamespace}-csf-exp`;
    const experimentId = uuid7();

    // Strictly increasing instants, one millisecond apart, backdated so every
    // minted id sits in the past — staging runs UUIDv7 validation with a
    // 24-hour window and refuses an id from the future.
    const base = Date.now() - 10_000;
    const rows: ComparisonStatsRowRef[] = CSF_ROWS.map((row, idx) => ({
      ...row,
      idx,
      datasetItemId: uuid7(new Date(base + idx)),
      traceId: uuid7(new Date(base + idx)),
    }));

    // The premise the whole spec rests on, asserted against the table above
    // rather than trusted: each single-row filter must match exactly one row,
    // and the two-row filter exactly two. A seed whose `gold` or `WRONG` also
    // occurred elsewhere would make a stats answer over the wrong row set
    // agree with the expected p50 by coincidence.
    const matching = (predicate: (row: ComparisonStatsRowRef) => boolean): number =>
      rows.filter(predicate).length;
    expect(matching((row) => row.tier.includes('gold')), '[comparisonStatsFilters] one gold tier')
      .toBe(1);
    expect(
      matching((row) => row.answer.includes('WRONG') || row.tier.includes('WRONG')),
      '[comparisonStatsFilters] "WRONG" occurs in exactly one row\'s output',
    ).toBe(1);
    expect(
      matching((row) => row.bucket.includes('beta')),
      '[comparisonStatsFilters] two beta buckets',
    ).toBe(2);
    expect(
      matching((row) => row.score !== null && row.score >= 0.5),
      '[comparisonStatsFilters] one row scores at or above 0.5',
    ).toBe(1);
    expect(
      matching((row) => row.score === null),
      '[comparisonStatsFilters] exactly one row is unscored',
    ).toBe(1);
    expect(
      new Set(rows.map((row) => row.durationMs)).size,
      '[comparisonStatsFilters] the three durations are pairwise different',
    ).toBe(rows.length);

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: `fixed-duration comparison for the statistics filters, seeded by ${testInfo.title}`,
    });

    let seededTraces = false;
    try {
      // `batchGroupId` COMMITS the write as v1 rather than mutating a latest
      // version that does not exist yet — and having a committed version is
      // what sends the statistics read through `DatasetItemVersionDAO`, which
      // is the class opik#9618 changed. `DatasetItemService.getExperimentItemsStats`
      // falls back to the legacy DAO for a dataset with NO versions, so a seed
      // that skipped this would exercise the other query entirely while every
      // assertion below still passed.
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        batchGroupId: crypto.randomUUID(),
        items: rows.map((row) => ({
          id: row.datasetItemId,
          data: { idx: row.idx, bucket: row.bucket },
        })),
      });

      // Asserted, not assumed: the version is the premise above, and a write
      // that landed without committing one is invisible in every later answer.
      expect(
        (await backendClient.getDatasetVersions(dataset.id)).map((v) => v.versionName),
        `[comparisonStatsFilters] ${datasetName} starts at exactly one committed version`,
      ).toEqual(['v1']);

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: rows.map((row) => ({
          id: row.traceId,
          name: `${testNamespace}-csf-trace-${row.idx}`,
          input: { idx: row.idx },
          output: { answer: row.answer, tier: row.tier },
          // Exact millisecond boundaries, because the duration the stats
          // quantile runs over is `dateDiff('microsecond', …) / 1000.0` — so a
          // whole number of milliseconds in is a whole number of milliseconds
          // out, and the expected p50 is arithmetic rather than a tolerance.
          startTime: new Date(base + row.idx),
          endTime: new Date(base + row.idx + row.durationMs),
        })),
      });
      seededTraces = true;

      const scored = rows.filter((row) => row.score !== null);
      await backendClient.setTraceFeedbackScores({
        projectName: project.name,
        scores: scored.map((row) => ({
          traceId: row.traceId,
          name: CSF_SCORE_NAME,
          value: row.score as number,
        })),
      });

      // Every trace queryable before the experiment is linked, so the debounce
      // window below is spent on the caller's reads rather than on ingestion.
      await expect
        .poll(
          async () =>
            (await backendClient.searchTraceIdsPage({ projectId: project.id, size: 50 })).total,
          {
            message:
              '[comparisonStatsFilters] every seeded trace must be queryable before linking',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(rows.length);

      // `running`, like `rawBranchExperiment`: an experiment still being
      // written to is what the seed is imitating, and it is the state the
      // un-aggregated read belongs to.
      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        status: 'running',
      });

      await backendClient.createExperimentItems(
        rows.map((row) => ({
          experimentId,
          datasetItemId: row.datasetItemId,
          traceId: row.traceId,
        })),
      );
      const writtenAtMs = Date.now();

      await waitForRows(backendClient, dataset.id, experimentId, rows.length);

      // The seed's own discrimination check, at the API, before any browser
      // opens: the unfiltered statistics read must already report all three
      // items and the p50 of all three durations. Without it, a filtered
      // assertion over a comparison that had only half landed would be
      // comparing one wrong number with another.
      const unfiltered = await backendClient.compareItemsStats({
        datasetId: dataset.id,
        experimentIds: [experimentId],
      });
      expect(
        unfiltered.status,
        `[comparisonStatsFilters] unfiltered stats for ${experimentName}: ${unfiltered.message}`,
      ).toBe(200);
      const countStat = unfiltered.stats.find((stat) => stat.name === 'experiment_items_count');
      expect(
        countStat?.value,
        '[comparisonStatsFilters] the unfiltered stats count every seeded experiment item',
      ).toBe(rows.length);
      const durationStat = unfiltered.stats.find((stat) => stat.name === 'duration');
      expect(
        (durationStat?.value as { p50?: unknown } | undefined)?.p50,
        '[comparisonStatsFilters] and report the p50 of the three seeded durations',
      ).toBe(CSF_ROWS[1].durationMs);

      const ref: ComparisonStatsFiltersRef = {
        projectId: project.id,
        projectName: project.name,
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        rows,
        scoreName: CSF_SCORE_NAME,
        writtenAtMs,
        rawBranchWindowMs: RAW_BRANCH_WINDOW_MS,
      };

      await testInfo.attach('opik.comparisonStatsFilters', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[comparisonStatsFilters fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe(`${rows.length} traces`, () =>
            backendClient.deleteTraces(rows.map((row) => row.traceId)),
          );
        }
      }
    }
  },
});

/**
 * Block until the comparison reports exactly `expected` rows.
 *
 * Exactly, not at-least: a statistics assertion over a half-landed seed would
 * compare one filtered answer against another computed over the same missing
 * rows, and agree.
 */
async function waitForRows(
  backendClient: {
    compareItemsPage: (args: {
      datasetId: string;
      experimentIds: string[];
      size?: number;
    }) => Promise<{ total: number }>;
  },
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (
      await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })
    ).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[comparisonStatsFilters fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect };
