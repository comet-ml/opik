import { test, expect } from '@e2e/fixtures';
import type { MixedScoreKind, MixedScoreResult } from '@e2e/core/sdk';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * A metric score that is not a finite real number becomes a FAILED score, and
 * the console, the experiment API and the experiment page all agree about the
 * average (opik#9396, issue 9395).
 *
 * `ScoreResult.value` is typed as a float, but nothing enforces it: a metric can
 * return NaN, infinity, `None`, or an object. Such a value cannot be averaged,
 * and the backend rejects a whole feedback-score batch containing NaN or
 * infinity — so one bad score used to take out the upload for every score beside
 * it, or poison the console average with a NaN. The engine now handles it like a
 * metric that RAISED (`mark_invalid_score_values_as_failed`):
 *
 * - `scoring_failed` is set and the value becomes `0.0`, so every view counts it
 *   as failed rather than as a deliberate zero;
 * - the score is not uploaded, so the batch is no longer rejected;
 * - `metadata.original_value` keeps a `repr()` of what the metric returned,
 *   capped at 200 characters by `reprlib` (NaN and infinity are not valid JSON,
 *   so it has to be a string);
 * - `metadata.original_reason` keeps the metric's own reason, which the engine's
 *   replacement reason would otherwise have overwritten.
 *
 * ## Why one run and not six
 *
 * The claim is about a MIXTURE. What has to hold is that the failed scores are
 * excluded from the average while the finite ones still form it — `avg 1.0` over
 * two surviving scores, with five reported failed, not `avg 0.17` over seven or
 * a NaN. Six single-value runs could not show that, and a run of only invalid
 * scores has no denominator at all, which is a different branch again
 * (`calculate_aggregated_statistics` drops the metric entirely).
 *
 * ## Why this is worth an e2e at all
 *
 * The PR covers it with unit tests, and this does not duplicate them: what no
 * unit test can show is the three views AGREEING against a real backend. The
 * console number comes from `ScoreStatistics`, the experiment header from
 * `GET /experiments/{id}`'s `feedback_scores`, and the latter only matches
 * because the failed scores were never uploaded. The one e2e that touches the
 * changed engine (`thread-evaluation.spec.ts`) uses a fixed finite score, so it
 * would pass with the rewrite removed.
 *
 * ## Surface
 *
 * Both. The run and the per-score facts come back through the Python bridge —
 * this is SDK behaviour and there is no UI that shows a `ScoreResult`'s
 * metadata — and the aggregate is then read through the REST API and off the
 * experiment page, which is where a disagreement between them would surface.
 */

/** The metric's own reason, which must survive onto `metadata.original_reason`. */
const METRIC_REASON = 'fixed reason from the mixed metric';

/**
 * Long enough that `reprlib` has to truncate it.
 *
 * The cap is 200 and `reprlib` splits the budget as 98 + '...' + 99 inside the
 * quotes, so anything comfortably over 200 exercises it. 260 is what the
 * exploration drove.
 */
const LONG_STRING_LENGTH = 260;

/** `_VALUE_REPR.maxstring` in `evaluation/engine/helpers.py`. */
const ORIGINAL_VALUE_CAP = 200;

/**
 * The seed, in dataset order: one finite score, the five invalid kinds, then a
 * second finite one.
 *
 * Two finite scores rather than one, at either end, so the surviving average is
 * over a denominator of 2 — a mean of 1.0 over ONE value would also be produced
 * by a build that dropped every score but the first.
 */
const SEED: Array<{ key: string; value_kind: MixedScoreKind }> = [
  { key: 'first-finite', value_kind: 'finite' },
  { key: 'not-a-number', value_kind: 'nan' },
  { key: 'positive-infinity', value_kind: 'pos_inf' },
  { key: 'negative-infinity', value_kind: 'neg_inf' },
  { key: 'none', value_kind: 'none' },
  { key: 'over-long-string', value_kind: 'long_string' },
  { key: 'second-finite', value_kind: 'finite' },
];

/** The five kinds the engine must rewrite. */
const INVALID_KINDS: MixedScoreKind[] = ['nan', 'pos_inf', 'neg_inf', 'none', 'long_string'];

/** The average the two surviving scores produce. */
const EXPECTED_MEAN = 1.0;

const byKey = (results: MixedScoreResult[], key: string): MixedScoreResult => {
  const match = results.filter((result) => result.key === key);
  expect(match, `exactly one score was recorded for item "${key}"`).toHaveLength(1);
  return match[0];
};

test.describe(
  'Experiments — a non-finite metric score reads as failed',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'the five invalid values become failed scores at 0.0, and console, API and page agree on avg 1',
      { tag: ['@cap:experiments.per-item-scores'] },
      async ({ project, sdkClient, backendClient, registerDatasetCleanup, registerExperimentCleanup, testNamespace, page }) => {
        const datasetName = `${testNamespace}-mixed-ds`;
        const experimentName = `${testNamespace}-mixed-exp`;
        const metricName = 'mixed_metric';

        const run = await test.step('Run evaluate() with one metric returning seven kinds of value', async () => {
          // Not wrapped in a try: "the run did not raise" is one of the claims,
          // and before 9396 a NaN reaching the feedback batch could fail the
          // upload for the whole experiment. A throw here surfaces as this
          // step's own failure, which is the right outcome.
          const result = await sdkClient.python.mixedScoreEvaluate({
            project_name: project.name,
            dataset_name: datasetName,
            experiment_name: experimentName,
            metric_name: metricName,
            reason: METRIC_REASON,
            items: SEED,
            long_string_length: LONG_STRING_LENGTH,
          });
          registerDatasetCleanup(result.dataset_id, datasetName);
          registerExperimentCleanup(result.experiment_id, experimentName);
          return result;
        });

        await test.step('Every item was scored exactly once', async () => {
          // The collection length before any per-item claim: a run that scored
          // five of seven items would otherwise let every assertion below pass
          // over the two it happened to include.
          expect(run.item_count, 'the dataset holds the seven seeded items').toBe(SEED.length);
          expect(
            run.results.map((result) => result.key).sort(),
            'one score per item, and no item scored twice',
          ).toEqual(SEED.map((item) => item.key).sort());
        });

        await test.step('The two finite scores were left alone', async () => {
          // The control. Without it, a build that marked EVERY score failed
          // would satisfy all five assertions below.
          for (const key of ['first-finite', 'second-finite']) {
            const score = byKey(run.results, key);
            expect(score.scoring_failed, `"${key}" scored a finite 1.0, so it did not fail`).toBe(
              false,
            );
            expect(score.score_value, `and its value is the one the metric returned`).toBe(1.0);
            expect(
              score.reason,
              'and it keeps the metric\'s own reason, not a replacement',
            ).toBe(METRIC_REASON);
            expect(
              score.original_value,
              'a score the engine left alone carries no original_value — there was no original ' +
                'to put aside',
            ).toBeNull();
          }
        });

        await test.step('The five invalid values became failed scores at 0.0', async () => {
          for (const kind of INVALID_KINDS) {
            const key = SEED.find((item) => item.value_kind === kind)!.key;
            const score = byKey(run.results, key);
            expect(
              score.scoring_failed,
              `"${key}" returned a ${kind}, which is not a finite number, so the score failed`,
            ).toBe(true);
            // 0.0 and not "falsy": the value is what every view counts, and the
            // pairing with `scoring_failed` is what stops it being read as a
            // deliberate zero.
            expect(
              score.score_value,
              `and its value was replaced with 0.0 rather than left unusable`,
            ).toBe(0.0);
            expect(
              score.original_reason,
              `and the metric's own reason moved to original_reason, where nothing overwrote it`,
            ).toBe(METRIC_REASON);
            expect(
              score.reason,
              `while the reason the user sees now says what was wrong with the value`,
            ).toContain('not a finite number');
            // Asserted present before anything is said about its contents: a
            // null here would make a length comparison below a comparison
            // against nothing.
            expect(
              score.original_value,
              `and a repr of what the metric returned was kept under original_value`,
            ).not.toBeNull();
          }
        });

        await test.step(`The over-long value's repr is capped at ${ORIGINAL_VALUE_CAP} characters`, async () => {
          const score = byKey(run.results, 'over-long-string');
          // The cap exists because an unbounded repr of an arbitrary object
          // goes into score metadata and then onto the wire. Exactly the cap,
          // not "at most": `reprlib` spends its whole budget, so a shorter
          // result means the cap moved or the truncation stopped happening.
          expect(
            score.original_value!.length,
            `a ${LONG_STRING_LENGTH}-character string is repr'd down to exactly the cap`,
          ).toBe(ORIGINAL_VALUE_CAP);
          expect(
            score.original_value,
            'and the truncation is visible in it, so the value is not silently misreported as whole',
          ).toContain('...');
        });

        await test.step('The console average is over the survivors, with the rest reported failed', async () => {
          // The numbers the report prints, read off the same `ScoreStatistics`
          // it uses. This is the half that used to be a NaN: averaging the
          // seven values, or the five zeros alongside them, gives neither 1.0
          // nor a usable number.
          expect(
            run.aggregate_mean,
            `the mean is ${EXPECTED_MEAN} — the two finite scores, with the five failed ones left out`,
          ).toBe(EXPECTED_MEAN);
          expect(
            run.aggregate_failed_count,
            'and what was left out is reported rather than silently shrinking the denominator',
          ).toBe(INVALID_KINDS.length);
          expect(
            run.aggregate_values,
            'the denominator is the two surviving values themselves, so a mean of 1.0 over one ' +
              'value cannot pass as a mean over two',
          ).toEqual([1.0, 1.0]);
        });

        await test.step('The experiment carries only the uploaded score', async () => {
          // The whole collection. A failed score is not uploaded, so its
          // ABSENCE here is the proof it never reached the feedback batch —
          // which is what stopped the backend rejecting the batch wholesale.
          // Polled because the experiment's aggregate is computed from scores
          // that are written asynchronously.
          await expect
            .poll(
              async () => backendClient.experimentFeedbackScores(run.experiment_id),
              {
                message:
                  'the experiment settles on one feedback score — the metric\'s average over its ' +
                  'two valid results',
                timeout: 60_000,
              },
            )
            .toEqual([{ name: metricName, value: EXPECTED_MEAN }]);
        });

        await test.step('The experiment page shows the same average over all seven rows', async () => {
          const experiments = new ExperimentsPage(page);
          await experiments.goto(project.id);
          await experiments.waitForReady();
          const detail = await experiments.openExperimentById(run.experiment_id);
          await detail.waitForReady();

          expect(
            await detail.readAggregateScore(),
            'the header chip reads the same average the console and the API reported — the three ' +
              'views agreeing is the point, and a failed score counted in any one of them breaks it',
          ).toBe(EXPECTED_MEAN);
          // The rows as well as the average: an experiment that had dropped
          // the five failed items would also show an average of 1.0, and would
          // be a worse bug than the one this spec is about.
          expect(
            await detail.countItems(),
            'and every seeded item is listed, including the five whose score failed',
          ).toBe(SEED.length);
        });
      },
    );
  },
);
