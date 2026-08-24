import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface WideComparisonExperimentRef {
  experimentId: string;
  experimentName: string;
  /** Trace ids linked to this experiment, aligned by index with `itemIds`. */
  traceIds: string[];
}

export interface WideComparisonRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  /** Shared dataset item ids, in seed order — index 0 is item `i0`. */
  itemIds: string[];
  /** Dataset field names, in the order the grid renders them (it sorts by name). */
  datasetFields: string[];
  /** Feedback-score metric names, in the order the grid renders them. */
  metricNames: string[];
  /** The single evaluation-task output key every trace writes. */
  outputKey: string;
  /** Experiments in the order they are passed to the compare view. */
  experiments: WideComparisonExperimentRef[];
}

export interface WideComparisonFixtures {
  wideComparison: WideComparisonRef;
}

const ITEM_COUNT = 4;
const EXPERIMENT_COUNT = 4;
/** Extra dataset fields on top of `input` / `expected_output`. */
const EXTRA_FIELD_COUNT = 10;
const METRIC_COUNT = 20;

export const OUTPUT_KEY = 'output';

const pad2 = (n: number): string => String(n).padStart(2, '0');

/** Dataset field names as the grid orders them: alphabetically by name. */
export const wideComparisonDatasetFields = (): string[] =>
  [
    ...Array.from({ length: EXTRA_FIELD_COUNT }, (_, i) => `ds_f${pad2(i + 1)}`),
    'expected_output',
    'input',
  ].sort((a, b) => a.localeCompare(b));

export const wideComparisonMetricNames = (): string[] =>
  Array.from({ length: METRIC_COUNT }, (_, i) => `m${pad2(i + 1)}`);

/**
 * The three value encodings below all carry their own coordinates, which is
 * what makes a silently mis-rendered grid fail rather than pass. A column
 * dropped mid-window shears every value one place to the left; if the values
 * were interchangeable ("0.5" everywhere) the shear would look like a healthy
 * table.
 */
export const datasetFieldValue = (itemIndex: number, field: string): string =>
  `i${itemIndex}-${field}`;

export const taskOutputValue = (experimentIndex: number, itemIndex: number): string =>
  `e${experimentIndex}-i${itemIndex}-out`;

/**
 * Metric index in the integer part, experiment in the first decimal, item in
 * the second: metric #7 / experiment #3 / item #2 scores 7.32. A one-column
 * shear therefore changes the integer part, a band read off the wrong
 * experiment changes the first decimal, and the wrong row the second — each
 * failure mode names itself in the diff.
 */
export const metricScoreValue = (
  metricIndex: number,
  experimentIndex: number,
  itemIndex: number,
): number => metricIndex + 1 + experimentIndex / 10 + itemIndex / 100;

/**
 * A comparison wide enough that the Results grid virtualizes its columns:
 * 12 dataset fields x 4 experiments x 20 feedback-score metrics, which is 39
 * leaf columns over ~5750px against a ~1040px scroller. The estate's
 * `comparison` fixture seeds 3 items / 2 experiments / 1 metric — a grid that
 * barely overflows one viewport — so nothing there ever leaves the first
 * column window.
 *
 * Seeded through the REST client rather than the SDK bridge because the bridge
 * has no route that writes an arbitrary set of feedback scores, and because the
 * ids are needed up front: the REST writes answer 204 with no body and this
 * fixture asserts on exact trace ids.
 *
 * Scores are written BEFORE the experiment items are linked, deliberately. The
 * comparison read (`/v1/private/datasets/{id}/items/experiments/items`)
 * snapshots an item's feedback scores at link time, so a score written after
 * linking shows up on the trace and never in the grid.
 *
 * Teardown lives here so it survives a mid-test failure, and it deletes more
 * than the project does: deleting a project removes neither its traces nor the
 * datasets and experiments that reference it.
 */
export const test = baseTest.extend<WideComparisonFixtures>({
  wideComparison: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-wide-ds`;
    const datasetFields = wideComparisonDatasetFields();
    const metricNames = wideComparisonMetricNames();

    const seedItems = Array.from({ length: ITEM_COUNT }, (_, itemIndex) =>
      Object.fromEntries(
        datasetFields.map((field) => [field, datasetFieldValue(itemIndex, field)]),
      ),
    );

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'wide compare grid — column virtualization',
      items: seedItems,
    });

    // Item ids are minted by the backend, so map each one back to its seed
    // index through the `input` field rather than trusting read-back order.
    const storedItems = await backendClient.getDatasetItems(dataset.id);
    const itemIds = Array.from({ length: ITEM_COUNT }, (_, itemIndex) => {
      const expected = datasetFieldValue(itemIndex, 'input');
      const match = storedItems.filter((i) => i.data.input === expected);
      if (match.length !== 1) {
        throw new Error(
          `[wideComparison fixture] expected exactly one dataset item with input "${expected}", found ${match.length}`,
        );
      }
      return match[0].id;
    });

    const experiments: WideComparisonExperimentRef[] = [];
    for (let experimentIndex = 0; experimentIndex < EXPERIMENT_COUNT; experimentIndex++) {
      const experimentId = uuid7();
      const experimentName = `${testNamespace}-wide-e${experimentIndex}`;

      const traceIds: string[] = [];
      for (let itemIndex = 0; itemIndex < ITEM_COUNT; itemIndex++) {
        const traceId = uuid7();
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-e${experimentIndex}-i${itemIndex}`,
          source: 'experiment',
          input: { input: datasetFieldValue(itemIndex, 'input') },
          output: { [OUTPUT_KEY]: taskOutputValue(experimentIndex, itemIndex) },
        });
        traceIds.push(traceId);
      }

      await backendClient.scoreTraces(
        traceIds.flatMap((traceId, itemIndex) =>
          metricNames.map((name, metricIndex) => ({
            traceId,
            projectName: project.name,
            name,
            value: metricScoreValue(metricIndex, experimentIndex, itemIndex),
          })),
        ),
      );

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });
      await backendClient.createExperimentItems(
        traceIds.map((traceId, itemIndex) => ({
          experimentId,
          datasetItemId: itemIds[itemIndex],
          traceId,
        })),
      );

      experiments.push({ experimentId, experimentName, traceIds });
    }

    const ref: WideComparisonRef = {
      projectId: project.id,
      projectName: project.name,
      datasetId: dataset.id,
      datasetName,
      itemIds,
      datasetFields,
      metricNames,
      outputKey: OUTPUT_KEY,
      experiments,
    };
    await testInfo.attach('opik.wideComparison', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[wideComparison fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the dataset they reference, traces before the
      // project fixture removes the project around them.
      for (const experiment of experiments) {
        await safe(`experiment ${experiment.experimentId}`, () =>
          backendClient.deleteExperiment(experiment.experimentId),
        );
      }
      const traceIds = experiments.flatMap((e) => e.traceIds);
      await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect } from './automation-rules.fixture';
