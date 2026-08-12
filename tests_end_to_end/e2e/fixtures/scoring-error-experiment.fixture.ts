import { test as baseTest } from './filterable-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ScoringErrorItemSeed {
  input: string;
  expected_output: string;
  task_output: string;
}

export interface ScoringErrorItemResult {
  datasetItemId: string;
  traceId: string | null;
  metricName: string;
  value: number;
  scoringFailed: boolean;
  errorExceptionType: string | null;
}

export interface ScoringErrorExperimentRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  items: ScoringErrorItemSeed[];
  /** Metric that scores cleanly — declares no **kwargs, so it exercises the narrowing branch. */
  passingMetricName: string;
  /** Metric requiring a `context` argument nothing supplies, so it can never score. */
  failingMetricName: string;
  /** Run at evaluate()'s shipped default error tolerance — expected to abort. */
  aborting: {
    aborted: boolean;
    exceptionType: string | null;
    message: string | null;
    experimentName: string;
  };
  /** Run at ErrorTolerance.ALL_SCORING_ERRORS — expected to complete. */
  tolerated: {
    experimentId: string;
    experimentName: string;
    aggregateScoreNames: string[];
    aggregateMeans: Record<string, number>;
    itemResults: ScoringErrorItemResult[];
  };
}

export interface ScoringErrorExperimentFixtures {
  scoringErrorExperiment: ScoringErrorExperimentRef;
}

/**
 * Same 2-pass-1-fail shape as the `experiment` fixture, so the passing metric's
 * mean is the familiar 2/3 and a metric that silently scored everything the
 * same would be visible. The rows carry no `context` key — that absence is what
 * makes the failing metric fail, deterministically, for every item.
 */
const SEED_ITEMS: ScoringErrorItemSeed[] = [
  { input: 'What is 2 + 2?', expected_output: '4', task_output: '4' },
  { input: 'What is the capital of France?', expected_output: 'Paris', task_output: 'Paris' },
  { input: 'What is 1 + 1?', expected_output: '2', task_output: 'NOT_TWO' },
];

export const test = baseTest.extend<ScoringErrorExperimentFixtures>({
  scoringErrorExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-scoring-err-ds`;
    const abortingExperimentName = `${testNamespace}-scoring-err-abort`;
    const toleratedExperimentName = `${testNamespace}-scoring-err-tolerated`;

    // Both runs are seeded together, over one shared dataset, so the only
    // difference between them is the error tolerance. Splitting them across two
    // fixtures would let the dataset drift and make the contrast meaningless.
    const seeded = await sdkClient.python.scoringErrorSeed({
      project_name: project.name,
      dataset_name: datasetName,
      items: SEED_ITEMS as unknown as Array<Record<string, unknown>>,
      aborting_experiment_name: abortingExperimentName,
      tolerated_experiment_name: toleratedExperimentName,
    });

    const ref: ScoringErrorExperimentRef = {
      datasetId: seeded.dataset_id,
      datasetName,
      projectName: project.name,
      items: SEED_ITEMS,
      passingMetricName: seeded.passing_metric_name,
      failingMetricName: seeded.failing_metric_name,
      aborting: {
        aborted: seeded.aborting.aborted,
        exceptionType: seeded.aborting.exception_type,
        message: seeded.aborting.message,
        experimentName: seeded.aborting.experiment_name,
      },
      tolerated: {
        experimentId: seeded.tolerated.experiment_id,
        experimentName: seeded.tolerated.experiment_name,
        aggregateScoreNames: seeded.tolerated.aggregate_score_names,
        aggregateMeans: seeded.tolerated.aggregate_means,
        itemResults: seeded.tolerated.item_results.map((r) => ({
          datasetItemId: r.dataset_item_id,
          traceId: r.trace_id,
          metricName: r.metric_name,
          value: r.value,
          scoringFailed: r.scoring_failed,
          errorExceptionType: r.error_exception_type,
        })),
      },
    };

    await testInfo.attach('opik.scoring-error-experiment', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Teardown order: experiments first (they reference the dataset), then the dataset. */
    if (!shouldLeaveArtifacts(testInfo)) {
      // The aborted run registers its experiment before it fails, so it needs
      // sweeping too — but its id only became readable once the bridge flushed
      // its client, so resolve it by name here rather than trusting the seed.
      const abortedExperiment = await backendClient
        .findExperimentByName(ref.aborting.experimentName)
        .catch(() => null);
      const experimentIds = [ref.tolerated.experimentId, abortedExperiment?.id].filter(
        (id): id is string => Boolean(id),
      );
      for (const id of experimentIds) {
        try {
          await backendClient.deleteExperiment(id);
        } catch (err) {
          console.warn(`[scoring-error fixture] delete experiment warning for ${id}:`, err);
        }
      }
      try {
        await backendClient.deleteDataset(seeded.dataset_id);
      } catch (err) {
        console.warn(`[scoring-error fixture] delete dataset warning for ${datasetName}:`, err);
      }
    }
  },
});

export { expect } from './filterable-traces.fixture';
