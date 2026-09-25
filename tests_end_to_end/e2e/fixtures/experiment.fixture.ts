import { test as baseTest } from './dataset.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ExperimentItemSeed {
  input: string;
  expected_output: string;
  task_output: string;
}

export interface ExperimentItemScore {
  datasetItemId: string;
  input: string;
  expectedOutput: string;
  taskOutput: string;
  scoreName: string;
  scoreValue: number;
}

export interface ExperimentRef {
  experimentId: string;
  experimentName: string;
  datasetId: string;
  datasetName: string;
  projectName: string;
  items: ExperimentItemSeed[];
  evaluator: { name: string; type: 'Equals' };
  expectedScores: number[];
  scores: ExperimentItemScore[];
}

export interface ExperimentFixtures {
  experiment: ExperimentRef;
}

/**
 * 2-pass-1-fail seed: rows where task_output === expected_output score 1.0,
 * the mismatched row scores 0.0. Exercises both pass and fail surfaces so a
 * broken evaluator that silently scores everything 1.0 (or 0.0) is caught.
 */
const SEED_ITEMS: ExperimentItemSeed[] = [
  { input: 'What is 2 + 2?', expected_output: '4', task_output: '4' },
  { input: 'What is the capital of France?', expected_output: 'Paris', task_output: 'Paris' },
  { input: 'What is 1 + 1?', expected_output: '2', task_output: 'NOT_TWO' },
];

const EXPECTED_SCORES = [1.0, 1.0, 0.0];

export const test = baseTest.extend<ExperimentFixtures>({
  experiment: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-exp-ds`;
    const experimentName = `${testNamespace}-exp`;
    // Both ids are registered as soon as the seed call reports them: a failure
    // anywhere after that must tear down what already exists.
    let experimentId: string | null = null;
    let datasetId: string | null = null;
    let ref: ExperimentRef | null = null;
    try {
      const created = await sdkClient.python.evaluateExperiment({
        project_name: project.name,
        dataset_name: datasetName,
        experiment_name: experimentName,
        items: SEED_ITEMS as unknown as Array<Record<string, unknown>>,
      });
      experimentId = created.experiment_id;
      datasetId = created.dataset_id;

      const scores: ExperimentItemScore[] = created.scores.map((s) => ({
        datasetItemId: s.dataset_item_id,
        input: s.input,
        expectedOutput: s.expected_output,
        taskOutput: s.task_output,
        scoreName: s.score_name,
        scoreValue: s.score_value,
      }));

      ref = {
        experimentId: created.experiment_id,
        experimentName: created.experiment_name,
        datasetId: created.dataset_id,
        datasetName,
        projectName: project.name,
        items: SEED_ITEMS,
        evaluator: { name: 'equals_metric', type: 'Equals' },
        expectedScores: EXPECTED_SCORES,
        scores,
      };

      await testInfo.attach('opik.experiment', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // A fully built fixture follows shouldLeaveArtifacts; a partially built
      // one is always removed. Teardown order: experiment first (it references
      // the dataset), then dataset (it references the project). The project
      // fixture handles its own delete.
      if (ref === null || !shouldLeaveArtifacts(testInfo)) {
        if (experimentId !== null) {
          try {
            await backendClient.deleteExperiment(experimentId);
          } catch (err) {
            console.warn(`[experiment fixture] delete experiment warning for ${experimentName}:`, err);
          }
        }
        if (datasetId !== null) {
          try {
            await backendClient.deleteDataset(datasetId);
          } catch (err) {
            console.warn(`[experiment fixture] delete dataset warning for ${datasetName}:`, err);
          }
        }
      }
    }
  },
});

export { expect } from './dataset.fixture';
