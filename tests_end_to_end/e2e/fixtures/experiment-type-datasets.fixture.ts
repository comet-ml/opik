import { test as baseTest } from './otel-provider-alias-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded experiment: its id, and the type it was recorded under. */
export interface TypedExperimentRef {
  id: string;
  name: string;
  type: 'regular' | 'trial';
}

/** One seeded dataset and the experiments recorded against it. */
export interface ExperimentTypeDatasetRef {
  id: string;
  name: string;
  itemIds: string[];
  experiments: TypedExperimentRef[];
}

export interface ExperimentTypeDatasetsRef {
  /**
   * One REGULAR experiment and one later TRIAL. The subject of the delete that
   * removes ONLY the trial: the dataset still has a regular experiment, so its
   * stored `last_created_experiment_at` must survive untouched.
   */
  mixed: ExperimentTypeDatasetRef;
  /**
   * A single REGULAR experiment and nothing else, so deleting it leaves the
   * dataset with no experiments at all. This is what proves the listener RUNS:
   * without it, "the stamp did not change" above would pass equally well on a
   * backend whose post-delete bookkeeping never fires.
   *
   * Regular-only rather than regular-plus-trial deliberately.
   * `FIND_MOST_RECENT_CREATED_EXPERIMENT_BY_DATASET_IDS` takes `max(created_at)`
   * over EVERY experiment of the dataset with no type filter, so a surviving
   * trial would simply become the new stamp — which is correct behaviour but
   * only assertable by comparing two timestamps that MySQL stores at microsecond
   * precision and ClickHouse at nanosecond. With no experiment left the stamp
   * goes to null, which is exact.
   */
  regularOnly: ExperimentTypeDatasetRef;
  /**
   * Two TRIALs and no regular experiment ever — the shape whose delete event
   * carries no REGULAR entry, which is the case opik#8607 guards.
   */
  trialOnly: ExperimentTypeDatasetRef;
  /** Every seeded dataset, so a test can sweep the ones it never touched. */
  all: ExperimentTypeDatasetRef[];
}

export interface ExperimentTypeDatasetsFixtures {
  experimentTypeDatasets: ExperimentTypeDatasetsRef;
}

/**
 * Two items per dataset, each of which gets an experiment item per experiment.
 *
 * Items are not optional garnish here: `most_recent_experiment_at` and
 * `experiment_count` are computed from `experiment_items`, so an experiment with
 * no items contributes nothing to either and a seed without them would leave
 * both figures null/zero — asserting "the count dropped by one" against a count
 * that was never anything but zero.
 */
const ITEMS_PER_DATASET = 2;

const DATASET_SHAPES: Array<{
  key: 'mixed' | 'regularonly' | 'trialonly';
  /** Creation order matters: `last_created_experiment_at` is the newest REGULAR. */
  experiments: Array<'regular' | 'trial'>;
}> = [
  { key: 'mixed', experiments: ['regular', 'trial'] },
  { key: 'regularonly', experiments: ['regular'] },
  { key: 'trialonly', experiments: ['trial', 'trial'] },
];

/**
 * Three datasets whose experiments differ only in TYPE, each with real
 * experiment items so the dataset summary has something to count.
 *
 * `datasets.last_created_experiment_at` is maintained by `DatasetEventListener`
 * and is REGULAR-only: a trial's creation is skipped, and a delete recomputes it
 * only for the datasets whose REGULAR experiments were in the event. That makes
 * it the one field a broken post-delete listener leaves permanently wrong —
 * `experiment_count` and `most_recent_experiment_at` are re-derived from
 * `experiment_items` on every read, so they cannot stay stale whatever the
 * listener does.
 *
 * Every test here deletes something, and the other two datasets are its
 * bystanders: "the target's count dropped" would be satisfied just as well by a
 * delete that took every experiment in the workspace, and the two survivors have
 * deliberately different shapes so a sweep cannot be mistaken for a no-op.
 *
 * Seeded over REST rather than through `evaluate()`: the experiment TYPE is the
 * whole variable, and the SDK's evaluate path only ever writes REGULAR.
 *
 * Teardown deletes the experiments before the datasets they reference, and
 * tolerates an experiment a test has already deleted — that is the normal case
 * here, not an error.
 */
export const test = baseTest.extend<ExperimentTypeDatasetsFixtures>({
  experimentTypeDatasets: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasets: ExperimentTypeDatasetRef[] = [];

    for (const shape of DATASET_SHAPES) {
      const datasetName = `${testNamespace}-${shape.key}-ds`;
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: `experiment-type bookkeeping: ${shape.experiments.join('+')}`,
        items: Array.from({ length: ITEMS_PER_DATASET }, (_, i) => ({
          input: `${shape.key} question ${i + 1}`,
          expected_output: `${shape.key} answer ${i + 1}`,
        })),
      });

      const itemIds = (await backendClient.getDatasetItems(created.id)).map((i) => i.id);
      if (itemIds.length !== ITEMS_PER_DATASET) {
        throw new Error(
          `[experimentTypeDatasets fixture] dataset '${datasetName}' stored ` +
            `${itemIds.length} items, expected ${ITEMS_PER_DATASET}`,
        );
      }

      const experiments: TypedExperimentRef[] = [];
      // Serially, and in the order the shape lists: `last_created_experiment_at`
      // is the newest REGULAR experiment's stamp, so an out-of-order seed would
      // make "the stamp belongs to the surviving regular experiment" ambiguous.
      for (const [index, type] of shape.experiments.entries()) {
        const experimentId = uuid7();
        const experimentName = `${testNamespace}-${shape.key}-${type}-${index + 1}`;
        await backendClient.createExperiment({
          id: experimentId,
          name: experimentName,
          datasetName,
          projectName: project.name,
          type,
        });

        // One trace per dataset item, minted here so the experiment items can
        // name them. Traces go with the project fixture, so they need no
        // teardown of their own.
        const traceIds = itemIds.map(() => uuid7());
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: traceIds.map((id, i) => ({
            id,
            name: `${experimentName}-trace-${i + 1}`,
            input: { question: `${shape.key} question ${i + 1}` },
            output: { answer: `${shape.key} answer ${i + 1}` },
          })),
        });
        await backendClient.createExperimentItems(
          itemIds.map((datasetItemId, i) => ({
            experimentId,
            datasetItemId,
            traceId: traceIds[i],
          })),
        );

        experiments.push({ id: experimentId, name: experimentName, type });
      }

      datasets.push({ id: created.id, name: datasetName, itemIds, experiments });
    }

    const byKey = (key: string): ExperimentTypeDatasetRef => {
      const found = datasets.find((d) => d.name.endsWith(`-${key}-ds`));
      if (!found) throw new Error(`[experimentTypeDatasets fixture] no '${key}' dataset`);
      return found;
    };

    const ref: ExperimentTypeDatasetsRef = {
      mixed: byKey('mixed'),
      regularOnly: byKey('regularonly'),
      trialOnly: byKey('trialonly'),
      all: datasets,
    };

    try {
      await testInfo.attach('opik.experimentTypeDatasets', {
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
            console.warn(`[experimentTypeDatasets fixture] delete warning for ${what}:`, err);
          }
        };
        for (const dataset of datasets) {
          // Experiments before the dataset they reference. `deleteExperiment`
          // swallows a 404, which is the expected answer for whichever ones the
          // test under way already deleted.
          for (const experiment of dataset.experiments) {
            await safe(`experiment ${experiment.id}`, () =>
              backendClient.deleteExperiment(experiment.id),
            );
          }
          await safe(`dataset ${dataset.name}`, () => backendClient.deleteDataset(dataset.id));
        }
      }
    }
  },
});

export { expect } from './otel-provider-alias-spans.fixture';
