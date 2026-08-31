import { test, expect } from '@e2e/fixtures';
import { OptimizationsListPage } from '@e2e/pom/optimizations-list.page';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import type { BackendClient, OptimizationRef } from '@e2e/core/backend';

/**
 * What the Optimization runs list reports for a run (OPIK-8060 / opik PR 7997).
 *
 * The row's Best score, Latency and Cost all claim to describe one candidate —
 * "the best trial" — so the failure that matters is not an empty cell but three
 * cells describing three different trials, or agreeing with each other while
 * disagreeing with the run page a click away. Before PR 7997 every row simply
 * repeated the baseline's numbers at a flat 0%; the fix added a partial-
 * evaluation gate so that a trial scored on a subset of the dataset cannot be
 * crowned on a partial average. No spec read a single cell of this page.
 *
 * Everything is seeded over REST with fixed durations and fixed span costs, so
 * every expected number below is arithmetic over values the fixture wrote — no
 * optimizer, no LLM, no price table.
 */

/** Poll the run's REST rollups until ClickHouse has aggregated the seeded trials. */
async function settledRollup(
  backendClient: BackendClient,
  optimizationId: string,
): Promise<OptimizationRef> {
  await expect
    .poll(
      async () => (await backendClient.getOptimization(optimizationId))?.baselineDuration ?? null,
      {
        message: 'the run\'s duration/cost rollups should materialise',
        timeout: 120_000,
        intervals: [1_000, 2_000, 5_000],
      },
    )
    .not.toBeNull();

  const run = await backendClient.getOptimization(optimizationId);
  expect(run, 'the seeded optimization should be readable over REST').not.toBeNull();
  return run!;
}

test.describe(
  'Optimization runs list — best-trial metrics',
  { tag: ['@t2-cuj', '@area:optimization-studio'] },
  () => {
    // Seeding a run costs several REST round trips per trace, and the rollups it
    // then has to wait on are computed asynchronously. Set on the describe, not
    // with `test.setTimeout()` in a body: fixture setup runs BEFORE the body, so
    // an in-body call cannot extend the budget the seeding itself spends.
    test.describe.configure({ timeout: 300_000 });

    test(
      'A fully evaluated run reports its best trial\'s score, latency and cost against the baseline',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ fullyEvaluatedRun, backendClient, page }) => {
        const run = fullyEvaluatedRun;

        await test.step('The API rolls up the trial\'s figures, not the baseline\'s', async () => {
          const rollup = await settledRollup(backendClient, run.optimizationId);
          expect(rollup.bestObjectiveScore).toBeCloseTo(0.9, 6);
          expect(rollup.baselineObjectiveScore).toBeCloseTo(0.2, 6);
          expect(rollup.bestDuration).toBeCloseTo(1, 3);
          expect(rollup.baselineDuration).toBeCloseTo(4, 3);
          expect(rollup.bestCost).toBeCloseTo(0.004, 6);
          expect(rollup.baselineCost).toBeCloseTo(0.008, 6);
          expect(rollup.totalOptimizationCost).toBeCloseTo(run.totalCost, 6);
        });

        const list = new OptimizationsListPage(page, run.projectId);
        await list.goto();
        await list.waitForRun(run.optimizationId);

        await test.step('The project lists this run and nothing else', async () => {
          await expect(list.runRows).toHaveCount(1);
        });

        await test.step('Best score is the trial\'s, under the run\'s objective', async () => {
          await expect(list.bestScoreLabel(run.optimizationId)).toHaveText(run.objectiveName);
          await expect(list.bestScoreValue(run.optimizationId)).toHaveText('0.9');
        });

        await test.step('Latency and Cost are the trial\'s, with their own deltas', async () => {
          // 4s -> 1s is -75%; $0.008 -> $0.004 is -50%. The two deltas differ on
          // purpose: a cell reading its neighbour's value would still be wrong
          // here, where a shared percentage would hide it.
          await expect(list.cell(run.optimizationId, 'latency')).toHaveText(/^-75%\s*1s$/);
          await expect(list.cell(run.optimizationId, 'cost')).toHaveText(/^-50%\s*\$0\.0040$/);
        });

        await test.step('Optimization cost totals the whole run\'s spend', async () => {
          // Every trial's traces, baseline included: 3 x $0.008 + 3 x $0.004.
          await expect(list.cell(run.optimizationId, 'opt_cost')).toHaveText('$0.036');
        });
      },
    );

    test(
      'Latency and Cost skip a partially evaluated trial and report the best complete one',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ partiallyEvaluatedRun, backendClient, page }) => {
        const run = partiallyEvaluatedRun;

        await test.step('The fixture really did leave one trial partially evaluated', async () => {
          // Without this the rest of the test is vacuous: if every candidate had
          // been evaluated on every item, the gate under test would never engage
          // and the assertions below would pass for the wrong reason.
          const rollup = await settledRollup(backendClient, run.optimizationId);
          const ungated = rollup.feedbackScores.find((s) => s.name === run.objectiveName);
          expect(ungated, `the run should carry an ungated "${run.objectiveName}" score`)
            .toBeDefined();
          expect(
            ungated!.value,
            'the partially evaluated trial should be the highest scorer of all',
          ).toBeCloseTo(1, 6);
          expect(
            rollup.bestObjectiveScore,
            'the gated rollup should ignore it and settle on the fully evaluated trial',
          ).toBeCloseTo(0.6, 6);
        });

        await test.step('The run page marks the fully evaluated trial as best', async () => {
          const studio = new OptimizationStudioPage(page, run.projectId);
          await studio.gotoDetail(run.optimizationId);
          await studio.openTrialsTab();
          await expect(studio.trialRowByLabel('Trial #2')).toHaveCount(1);
          await expect(studio.trialCell('Trial #2', 'trial_status')).toHaveText('Best');
          await expect(
            studio.trialCell('Trial #1', 'trial_status'),
            'the trial evaluated on 1 of 3 items is not the run\'s best',
          ).not.toHaveText('Best');
        });

        const list = new OptimizationsListPage(page, run.projectId);
        await list.goto();
        await list.waitForRun(run.optimizationId);

        await test.step('Latency and Cost come from the fully evaluated trial', async () => {
          // Trial #2: 3s -> 2s is -33%, $0.009 -> $0.0045 is -50%. Trial #1 —
          // faster and cheaper still, at 1s and $0.001 — is excluded because it
          // was evaluated on 1 of the 3 items the baseline covered.
          await expect(list.cell(run.optimizationId, 'latency')).toHaveText(/^-33%\s*2s$/);
          await expect(list.cell(run.optimizationId, 'cost')).toHaveText(/^-50%\s*\$0\.0045$/);
        });
      },
    );

    /**
     * KNOWN FAILING against 2.2.45 — see the PR description.
     *
     * The Best score cell reads `getFeedbackScore(row.feedback_scores, …)`
     * (`OptimizationMetricCells.tsx`), and `feedback_scores` is
     * `maxMap(fs.feedback_scores)` in `OptimizationDAO` — a plain max over every
     * candidate with no partial-evaluation gate. Its Latency and Cost siblings
     * read `best_duration` / `best_cost`, which ARE gated. So on a run whose top
     * scorer was only partially evaluated, this row reports one trial's score
     * beside another trial's latency and cost, and contradicts the run page —
     * which marks the fully evaluated trial as Best — one click away.
     *
     * Asserted against the behaviour the fix intends. Left failing rather than
     * annotated: nothing has been filed for it yet, and `test.fail()` here would
     * turn an unreviewed observation into an accepted one.
     */
    test(
      'Best score skips a partially evaluated trial and reports the best complete one',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ partiallyEvaluatedRun, backendClient, page }) => {
        const run = partiallyEvaluatedRun;

        const gatedBest = await test.step('The gated rollup names the fully evaluated trial', async () => {
          const rollup = await settledRollup(backendClient, run.optimizationId);
          expect(rollup.bestObjectiveScore).toBeCloseTo(0.6, 6);
          return rollup.bestObjectiveScore!;
        });

        const list = new OptimizationsListPage(page, run.projectId);
        await list.goto();
        await list.waitForRun(run.optimizationId);

        await test.step('The Best score cell reports that same trial', async () => {
          await expect(list.bestScoreLabel(run.optimizationId)).toHaveText(run.objectiveName);
          await expect(
            list.bestScoreValue(run.optimizationId),
            'the row must not crown a trial the run page discarded',
          ).toHaveText(String(gatedBest));
        });
      },
    );

    test(
      'A run whose objective was never scored reports no best trial, but still its spend',
      { tag: ['@cap:optimization-studio.list-optimization-runs'] },
      async ({ unscoredObjectiveRun, backendClient, page }) => {
        const run = unscoredObjectiveRun;

        await test.step('The run carries scores, just not under its objective', async () => {
          const rollup = await settledRollup(backendClient, run.optimizationId);
          expect(
            rollup.feedbackScores.map((s) => s.name),
            'the traces are scored, so an empty cell cannot be blamed on missing scores',
          ).not.toHaveLength(0);
          expect(rollup.feedbackScores.find((s) => s.name === run.objectiveName)).toBeUndefined();
          expect(rollup.totalOptimizationCost).toBeCloseTo(run.totalCost, 6);
        });

        const list = new OptimizationsListPage(page, run.projectId);
        await list.goto();
        await list.waitForRun(run.optimizationId);

        await test.step('Best score, Latency and Cost are empty rather than the baseline\'s', async () => {
          // The deliberate behaviour change in PR 7997: with nothing eligible to
          // be "best", these cells must say so instead of quietly presenting the
          // baseline's figures as an improvement over itself.
          await expect(list.cell(run.optimizationId, 'accuracy')).toHaveText('-');
          await expect(list.cell(run.optimizationId, 'latency')).toHaveText('-');
          await expect(list.cell(run.optimizationId, 'cost')).toHaveText('-');
        });

        await test.step('Optimization cost still totals the run\'s spend', async () => {
          // 3 x $0.003 + 3 x $0.002 — unrelated to whether a trial won.
          await expect(list.cell(run.optimizationId, 'opt_cost')).toHaveText('$0.015');
        });
      },
    );
  },
);
