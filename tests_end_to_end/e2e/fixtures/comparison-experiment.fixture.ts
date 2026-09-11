import { test as baseTest } from './experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ComparisonItemSeed {
  input: string;
  expected_output: string;
}

export interface ComparisonExperimentRef {
  experimentId: string;
  experimentName: string;
  /** Score keyed by shared dataset item id. */
  scoresByItemId: Record<string, number>;
  /** Evaluation-task output keyed by shared dataset item id. */
  outputsByItemId: Record<string, string>;
  /** Mean of this experiment's per-item scores. */
  aggregateScore: number;
}

export interface ComparisonRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  items: ComparisonItemSeed[];
  /** Dataset item ids, aligned by index with `items` (shared across both experiments). */
  itemIds: string[];
  evaluator: { name: string };
  experiments: ComparisonExperimentRef[];
}

export interface ComparisonFixtures {
  comparison: ComparisonRef;
}

/**
 * Three shared dataset items scored by two experiments. The seed is tuned so a
 * comparison test can see everything that matters, all at once:
 *   item      expA out  expB out   expA  expB
 *   q1 -> A     A         A         pass  pass   (agree)
 *   q2 -> B     B         WRONG     pass  fail   (disagree)
 *   q3 -> C     C         WRONG     pass  fail   (disagree)
 *   aggregate mean:                 1.00  0.33
 * Properties this buys:
 *  - the two experiments DISAGREE per item (q2, q3) — side-by-side is meaningful;
 *  - their AGGREGATES differ (1.00 vs 0.33) — the Feedback scores tab is meaningful;
 *  - q1 is the UNIQUE highest-aggregate item (2 vs 1) — score-sort has a stable top.
 */
const SEED_ITEMS: ComparisonItemSeed[] = [
  { input: 'q1', expected_output: 'A' },
  { input: 'q2', expected_output: 'B' },
  { input: 'q3', expected_output: 'C' },
];

const EXPERIMENT_A_OUTPUTS = ['A', 'B', 'C'];
const EXPERIMENT_B_OUTPUTS = ['A', 'WRONG', 'WRONG'];

export const test = baseTest.extend<ComparisonFixtures>({
  comparison: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-cmp-ds`;
    const experimentNameA = `${testNamespace}-cmp-expA`;
    const experimentNameB = `${testNamespace}-cmp-expB`;

    const seeded = await sdkClient.python.compareSeed({
      project_name: project.name,
      dataset_name: datasetName,
      items: SEED_ITEMS,
      experiments: [
        { experiment_name: experimentNameA, task_outputs: EXPERIMENT_A_OUTPUTS },
        { experiment_name: experimentNameB, task_outputs: EXPERIMENT_B_OUTPUTS },
      ],
    });

    // itemId per seed input, so tests can address a shared item by its input.
    const itemIdByInput: Record<string, string> = {};
    for (const s of seeded.experiments[0].scores) {
      itemIdByInput[s.input] = s.dataset_item_id;
    }

    // The bridge doesn't echo task_output on the dataset item, so map each
    // experiment's per-item output from the seed arrays (aligned to SEED_ITEMS).
    const outputsBySeedIndex = [EXPERIMENT_A_OUTPUTS, EXPERIMENT_B_OUTPUTS];

    const experiments: ComparisonExperimentRef[] = seeded.experiments.map((exp, expIndex) => {
      const scoresByItemId = Object.fromEntries(exp.scores.map((s) => [s.dataset_item_id, s.score_value]));
      const outputsByItemId: Record<string, string> = {};
      SEED_ITEMS.forEach((item, i) => {
        outputsByItemId[itemIdByInput[item.input]] = outputsBySeedIndex[expIndex][i];
      });
      const scoreValues = Object.values(scoresByItemId);
      const aggregateScore = scoreValues.reduce((a, b) => a + b, 0) / scoreValues.length;
      return {
        experimentId: exp.experiment_id,
        experimentName: exp.experiment_name,
        scoresByItemId,
        outputsByItemId,
        aggregateScore,
      };
    });

    const ref: ComparisonRef = {
      datasetId: seeded.dataset_id,
      datasetName,
      projectName: project.name,
      items: SEED_ITEMS,
      itemIds: SEED_ITEMS.map((item) => itemIdByInput[item.input]),
      evaluator: { name: 'equals_metric' },
      experiments,
    };

    await testInfo.attach('opik.comparison', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const exp of experiments) {
        try {
          await backendClient.deleteExperiment(exp.experimentId);
        } catch (err) {
          console.warn(`[comparison fixture] delete experiment warning for ${exp.experimentName}:`, err);
        }
      }
      try {
        await backendClient.deleteDataset(seeded.dataset_id);
      } catch (err) {
        console.warn(`[comparison fixture] delete dataset warning for ${datasetName}:`, err);
      }
    }
  },
});

export { expect } from './experiment.fixture';
