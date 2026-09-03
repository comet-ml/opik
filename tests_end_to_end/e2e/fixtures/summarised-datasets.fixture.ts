import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * A dataset shape, chosen so no two datasets in the seed share a summary.
 *
 * The Datasets list does not store its per-row numbers — the backend derives
 * each from a separate lookup and zips them onto the row. The failure that
 * makes worth testing is therefore mis-attribution: a real number attached to
 * the wrong dataset, which renders as a completely plausible row. Symmetric
 * fixtures cannot catch it, because a swapped pair of identical summaries is
 * indistinguishable from a correct one.
 *
 * So the four shapes below are pairwise distinct as whole tuples: items /
 * experiments / optimizations / versions is (3,2,0,1), (5,0,1,1), (0,0,0,0),
 * (5,1,2,3). Any swap between any two rows changes at least one asserted value.
 */
export interface SummarisedDatasetShape {
  /** Short suffix, so a failure names which dataset disagreed. */
  key: string;
  /**
   * Items per `insert` call. Each call cuts exactly one version, so the array
   * length is the version count and its sum is the item count — which is how
   * `ds-d` gets 5 items spread over 3 versions.
   */
  itemsPerVersion: number[];
  /**
   * Experiments seeded WITH experiment items. The count is deliberate: the
   * backend counts experiments that have items, not experiment rows, so an
   * experiment seeded without them reports 0 and would look like a bug.
   */
  experiments: number;
  optimizations: number;
}

export const SUMMARISED_DATASET_SHAPES: SummarisedDatasetShape[] = [
  { key: 'a', itemsPerVersion: [3], experiments: 2, optimizations: 0 },
  { key: 'b', itemsPerVersion: [5], experiments: 0, optimizations: 1 },
  // The empty dataset. It is the `getOrDefault` path on every one of the four
  // lookups at once, and the only row whose `latest_version` must be null.
  { key: 'c', itemsPerVersion: [], experiments: 0, optimizations: 0 },
  { key: 'd', itemsPerVersion: [2, 2, 1], experiments: 1, optimizations: 2 },
];

export interface SummarisedDatasetRef {
  id: string;
  name: string;
  shape: SummarisedDatasetShape;
  /** Total items actually inserted — the sum of `shape.itemsPerVersion`. */
  itemCount: number;
  /** Number of versions cut — the length of `shape.itemsPerVersion`. */
  versionCount: number;
  experimentIds: string[];
  optimizationIds: string[];
}

export interface SummarisedDatasetsRef {
  projectId: string;
  projectName: string;
  datasets: SummarisedDatasetRef[];
}

export interface SummarisedDatasetsFixtures {
  summarisedDatasets: SummarisedDatasetsRef;
}

/**
 * Four datasets under one project with deliberately asymmetric shapes, so the
 * computed columns on the Datasets list can be checked row by row.
 *
 * Seeded over REST rather than through the SDK bridge because experiments,
 * experiment items and optimizations have to be attached to a dataset this
 * fixture already owns — the SDK's `evaluate` mints its own dataset, which is
 * the opposite of what is needed here. Ids are minted up front with `uuid7()`
 * because the REST writes answer 204 with no body.
 *
 * Nothing here launches an optimizer or calls a model: what is under test is
 * which dataset each summary belongs to, which is independent of how the
 * experiments and optimizations came to exist. That keeps the fixture
 * deterministic and LLM-free.
 *
 * Teardown deletes considerably more than the project does. `ProjectService`'s
 * delete removes only the project row, and `global-teardown`'s run-prefix sweep
 * does not know about traces at all — so experiments, optimizations, traces and
 * datasets are all removed explicitly, children before parents.
 */
export const test = baseTest.extend<SummarisedDatasetsFixtures>({
  summarisedDatasets: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasets: SummarisedDatasetRef[] = [];
    const traceIds: string[] = [];

    for (const shape of SUMMARISED_DATASET_SHAPES) {
      const name = `${testNamespace}-ds-${shape.key}`;
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name,
        description: `summary shape ${shape.key}`,
      });

      // One insert() call per version. Item payloads are unique per dataset so
      // content-hash dedup can never silently collapse two of them.
      let inserted = 0;
      for (const batchSize of shape.itemsPerVersion) {
        await sdkClient.python.insertDatasetItems({
          project_name: project.name,
          dataset_name: name,
          items: Array.from({ length: batchSize }, (_, i) => ({
            input: `${shape.key} item ${inserted + i}`,
            expected_output: `${shape.key} output ${inserted + i}`,
          })),
        });
        inserted += batchSize;
      }

      const datasetItemIds = (await backendClient.getDatasetItems(created.id)).map(
        (item) => item.id,
      );

      const experimentIds: string[] = [];
      for (let e = 0; e < shape.experiments; e++) {
        const experimentId = uuid7();
        const traceId = uuid7();
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-${shape.key}-exp-${e}`,
          source: 'experiment',
          input: { text: `${shape.key} experiment ${e}` },
          output: { text: 'ok' },
          endTime: new Date(),
        });
        traceIds.push(traceId);

        await backendClient.createExperiment({
          id: experimentId,
          name: `${testNamespace}-${shape.key}-exp-${e}`,
          datasetName: name,
          projectName: project.name,
        });
        // Without an experiment item the experiment does not count, so this is
        // load-bearing rather than decoration.
        await backendClient.createExperimentItems([
          { experimentId, datasetItemId: datasetItemIds[e % datasetItemIds.length], traceId },
        ]);
        experimentIds.push(experimentId);
      }

      const optimizationIds: string[] = [];
      for (let o = 0; o < shape.optimizations; o++) {
        const optimizationId = uuid7();
        await backendClient.createOptimization({
          id: optimizationId,
          name: `${testNamespace}-${shape.key}-opt-${o}`,
          datasetName: name,
          projectName: project.name,
          objectiveName: 'equals',
          status: 'completed',
        });
        optimizationIds.push(optimizationId);
      }

      datasets.push({
        id: created.id,
        name,
        shape,
        itemCount: inserted,
        versionCount: shape.itemsPerVersion.length,
        experimentIds,
        optimizationIds,
      });
    }

    const ref: SummarisedDatasetsRef = {
      projectId: project.id,
      projectName: project.name,
      datasets,
    };
    await testInfo.attach('opik.summarisedDatasets', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[summarisedDatasets fixture] delete warning for ${what}:`, err);
        }
      };
      for (const dataset of datasets) {
        for (const experimentId of dataset.experimentIds) {
          await safe(`experiment ${experimentId}`, () =>
            backendClient.deleteExperiment(experimentId),
          );
        }
        for (const optimizationId of dataset.optimizationIds) {
          await safe(`optimization ${optimizationId}`, () =>
            backendClient.deleteOptimization(optimizationId),
          );
        }
      }
      if (traceIds.length > 0) {
        await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
      }
      for (const dataset of datasets) {
        await safe(`dataset ${dataset.name}`, () => backendClient.deleteDataset(dataset.id));
      }
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
