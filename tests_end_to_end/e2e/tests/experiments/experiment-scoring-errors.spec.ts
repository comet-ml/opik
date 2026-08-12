import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * A scoring metric that cannot run is not the same thing as a metric that
 * scored 0, and the whole point of the tolerated-error path is that the
 * difference stays visible. These specs pin both halves of that:
 *
 *  - at the default error tolerance the evaluation still aborts, loudly;
 *  - at ALL_SCORING_ERRORS it completes, the failure is recorded per item and
 *    kept out of the aggregate, and the score cell ends up EMPTY rather than 0.
 *
 * Deliberately not a duplicate of experiments-smoke.spec.ts. That spec scores
 * with `Equals`, whose score() signature ends in **ignored_kwargs — every
 * offered key is absorbed, so the argument-narrowing branch never runs. The
 * metrics behind this fixture declare their arguments exactly, which is the
 * only way to reach that branch.
 */
test.describe('Experiments — scoring failures', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'a tolerated scoring failure is recorded per item and kept out of the aggregate',
    { tag: ['@cap:experiments.per-item-scores'] },
    async ({ scoringErrorExperiment }) => {
      const { passingMetricName, failingMetricName, tolerated, items } = scoringErrorExperiment;

      await test.step('Every item records the failing metric as scoring_failed with the right cause', async () => {
        const failing = tolerated.itemResults.filter((r) => r.metricName === failingMetricName);
        expect(failing, `one ${failingMetricName} result per seeded item`).toHaveLength(items.length);
        for (const result of failing) {
          expect(result.scoringFailed, `scoring_failed for item ${result.datasetItemId}`).toBe(true);
          expect(
            result.errorExceptionType,
            `error_info.exception_type for item ${result.datasetItemId}`,
          ).toBe('ScoreMethodMissingArguments');
        }
      });

      await test.step('The passing metric scored normally on the same run', async () => {
        const passing = tolerated.itemResults.filter((r) => r.metricName === passingMetricName);
        expect(passing, `one ${passingMetricName} result per seeded item`).toHaveLength(items.length);
        for (const result of passing) {
          expect(result.scoringFailed, `scoring_failed for item ${result.datasetItemId}`).toBe(false);
          expect(result.errorExceptionType, `error_info for item ${result.datasetItemId}`).toBeNull();
        }
        // 2-pass-1-fail seed: a metric that graded everything alike would not
        // produce both values.
        expect(passing.filter((r) => r.value === 1.0), 'passing rows').toHaveLength(2);
        expect(passing.filter((r) => r.value === 0.0), 'failing rows').toHaveLength(1);
      });

      await test.step('The SDK aggregate names only the metric that could score', async () => {
        expect(tolerated.aggregateScoreNames, 'aggregate score names').toEqual([passingMetricName]);
        expect(tolerated.aggregateMeans[passingMetricName], `${passingMetricName} mean`)
          .toBeCloseTo(2 / 3, 5);
      });
    },
  );

  test(
    'the persisted experiment carries no score at all for the failed metric',
    { tag: ['@cap:experiments.per-item-scores'] },
    async ({ scoringErrorExperiment, backendClient }) => {
      const { passingMetricName, failingMetricName, tolerated } = scoringErrorExperiment;

      const experiment = await test.step('Read the experiment back over the API', async () => {
        const found = await backendClient.findExperimentByName(tolerated.experimentName);
        expect(found, `experiment ${tolerated.experimentName} over the API`).not.toBeNull();
        return found!;
      });

      await test.step('Its feedback scores hold the passing metric average and nothing else', async () => {
        // Absence, not zero. A backend that persisted the failed metric as 0.0
        // would still return two entries here and would drag the reported
        // average of that metric to something a user would read as a real score.
        expect(experiment.feedbackScores.map((s) => s.name).sort(), 'persisted score names')
          .toEqual([passingMetricName]);
        expect(experiment.feedbackScores[0].value, `${passingMetricName} average`)
          .toBeCloseTo(2 / 3, 5);
        expect(
          experiment.feedbackScores.some((s) => s.name === failingMetricName),
          `${failingMetricName} must not be persisted at all`,
        ).toBe(false);
      });
    },
  );

  test(
    'the failure stays visible as an error span on the item trace',
    { tag: ['@cap:experiments.per-item-scores'] },
    async ({ scoringErrorExperiment, project, backendClient }) => {
      const { failingMetricName, passingMetricName, tolerated } = scoringErrorExperiment;

      const traceId = tolerated.itemResults.find(
        (r) => r.metricName === failingMetricName && r.traceId !== null,
      )?.traceId;
      expect(traceId, 'a trace id for a failed scoring result').toBeTruthy();

      const spans = await backendClient.listTraceSpans(project.id, traceId!);

      await test.step('The metric-argument validation span carries the error', async () => {
        // The span is named "<metric>_arg_validation", NOT "<metric>" — the
        // validation step is what failed, before score() was ever called.
        const validationSpan = spans.find((s) => s.name === `${failingMetricName}_arg_validation`);
        expect(validationSpan, `span ${failingMetricName}_arg_validation among ${spans.map((s) => s.name).join(', ')}`)
          .toBeTruthy();
        expect(validationSpan!.errorInfo, 'error_info on the validation span').not.toBeNull();
        expect(validationSpan!.errorInfo!.exceptionType, 'exception type').toBe(
          'ScoreMethodMissingArguments',
        );
        expect(validationSpan!.errorInfo!.message, 'exception message names the missing argument')
          .toContain('context');
      });

      await test.step('The passing metric on the same trace records no error', async () => {
        const passingSpan = spans.find((s) => s.name === passingMetricName);
        expect(passingSpan, `span ${passingMetricName}`).toBeTruthy();
        expect(passingSpan!.errorInfo, `error_info on ${passingMetricName}`).toBeNull();
      });
    },
  );

  test(
    'the experiment page shows an empty score cell for the failed metric, not a zero',
    { tag: ['@cap:experiments.per-item-scores'] },
    async ({ scoringErrorExperiment, project, page }) => {
      const { passingMetricName, failingMetricName, tolerated, items } = scoringErrorExperiment;
      const experiments = new ExperimentsPage(page);

      const detail = await test.step('Open the tolerated experiment', async () => {
        await experiments.goto(project.id);
        await experiments.waitForReady();
        const opened = await experiments.openExperimentById(tolerated.experimentId);
        await opened.waitForReady();
        return opened;
      });

      await test.step('Every seeded item is listed', async () => {
        expect(await detail.countItems()).toBe(items.length);
      });

      const passingResults = tolerated.itemResults.filter((r) => r.metricName === passingMetricName);

      await test.step('The passing metric renders its real per-item score', async () => {
        for (const result of passingResults) {
          const uiScore = await detail.readItemScore(result.datasetItemId, passingMetricName);
          expect(uiScore, `UI score for item ${result.datasetItemId}`).toBeCloseTo(result.value, 5);
        }
      });

      await test.step('The failed metric has no cell on any item — empty, not 0', async () => {
        // This is the assertion the whole spec exists for. A 0 here reads as
        // "the metric ran and the answer was wrong"; empty reads as "the metric
        // never ran". Only one of those is true.
        for (const result of passingResults) {
          await detail.expectNoScoreForMetric(result.datasetItemId, failingMetricName);
        }
      });

      await test.step('The aggregate chips name only the metric that could score', async () => {
        const names = await detail.aggregateScoreNames();
        expect(
          names.some((n) => n.includes(passingMetricName)),
          `aggregate chips ${JSON.stringify(names)} include ${passingMetricName}`,
        ).toBe(true);
        expect(
          names.some((n) => n.includes(failingMetricName)),
          `aggregate chips ${JSON.stringify(names)} must not mention ${failingMetricName}`,
        ).toBe(false);
      });
    },
  );

  test(
    'the default error tolerance still aborts the run instead of tolerating the failure',
    { tag: ['@cap:experiments.per-item-scores'] },
    async ({ scoringErrorExperiment }) => {
      const { aborting, failingMetricName } = scoringErrorExperiment;

      await test.step('evaluate() raised rather than returning a result', async () => {
        // The tolerated path only makes sense if the default is still strict.
        // If a future default silently swallowed scoring errors, every existing
        // evaluation would start reporting partial aggregates with no signal.
        expect(aborting.aborted, `run ${aborting.experimentName} at the default tolerance`).toBe(true);
        expect(aborting.exceptionType, 'exception type').toBe('ScoreMethodMissingArguments');
      });

      await test.step('The error names the metric and the argument it could not bind', async () => {
        expect(aborting.message, 'exception message').toContain(failingMetricName);
        expect(aborting.message, 'exception message').toContain('context');
      });
    },
  );
});
