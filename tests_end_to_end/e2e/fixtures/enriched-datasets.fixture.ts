import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface EnrichedDatasetSeedRef {
  id: string;
  name: string;
  /** Items actually stored, verified against the seed before the test starts. */
  itemCount: number;
}

export interface EnrichedDatasetsRef {
  projectId: string;
  projectName: string;
  /** 7 items, one experiment (2 experiment items) and one optimization. */
  busy: EnrichedDatasetSeedRef;
  /** 3 items and nothing else — the sibling every counter must stay off. */
  quiet: EnrichedDatasetSeedRef;
  experimentId: string;
  optimizationId: string;
  /** Traces the experiment items point at; they do not cascade with the project. */
  traceIds: string[];
}

export interface EnrichedDatasetsFixtures {
  enrichedDatasets: EnrichedDatasetsRef;
}

/**
 * Two datasets in one project with deliberately different item counts, so a row
 * that renders its neighbour's number is a failure rather than a coincidence.
 * 7 and 3 also differ from the experiment and optimization counts (1 and 1), so
 * no counter can be satisfied by another counter's value.
 */
const BUSY_ITEM_COUNT = 7;
const QUIET_ITEM_COUNT = 3;

/** Fewer than the dataset's items, so experiment items cannot pass as item count. */
const EXPERIMENT_ITEM_COUNT = 2;

const OBJECTIVE = 'equals';

function seedItems(count: number, tag: string): Array<Record<string, unknown>> {
  return Array.from({ length: count }, (_, i) => ({
    input: `${tag} question ${i + 1}`,
    expected_output: `${tag} answer ${i + 1}`,
  }));
}

/**
 * The seed for the Datasets list's per-row enrichment: one dataset that every
 * enriched counter should have something to say about, and one in the same
 * project that they must all leave at zero.
 *
 * The pairing is the point. `enrichDatasetWithAdditionalInformation` fans its
 * four lookups out and zips the answers back onto the rows, so the failures
 * worth catching are a value landing on the wrong row and a lookup returning
 * nothing being read as a legitimate zero — neither of which a single seeded
 * dataset can distinguish.
 *
 * Seeded through the REST client rather than a real evaluation run: what the
 * enrichment counts is experiment and optimization *rows* against the dataset,
 * which is independent of how an evaluator or optimizer produced them. That
 * keeps the fixture deterministic and LLM-free.
 *
 * Teardown deletes more than the project does: `ProjectService.delete` removes
 * only the project row, so traces do not cascade, and datasets, experiments and
 * optimizations all need explicit deletes.
 */
export const test = baseTest.extend<EnrichedDatasetsFixtures>({
  enrichedDatasets: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const busyName = `${testNamespace}-busy-ds`;
    const quietName = `${testNamespace}-quiet-ds`;
    const experimentId = uuid7();
    const optimizationId = uuid7();

    const seedDataset = async (
      name: string,
      itemCount: number,
      tag: string,
    ): Promise<EnrichedDatasetSeedRef> => {
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name,
        description: 'datasets list enrichment',
        items: seedItems(itemCount, tag),
      });
      const stored = await backendClient.getDatasetItems(created.id);
      if (stored.length !== itemCount) {
        throw new Error(
          `[enrichedDatasets fixture] ${name}: expected ${itemCount} items, got ${stored.length}`,
        );
      }
      return { id: created.id, name: created.name, itemCount };
    };

    const busy = await seedDataset(busyName, BUSY_ITEM_COUNT, 'busy');
    const quiet = await seedDataset(quietName, QUIET_ITEM_COUNT, 'quiet');

    const busyItemIds = (await backendClient.getDatasetItems(busy.id)).map((i) => i.id);

    const traceIds: string[] = [];
    for (let i = 0; i < EXPERIMENT_ITEM_COUNT; i++) {
      const id = uuid7();
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name: `${testNamespace}-exp-trace-${i + 1}`,
        source: 'experiment',
        input: { question: `busy question ${i + 1}` },
        output: { answer: `busy answer ${i + 1}` },
      });
      traceIds.push(id);
    }

    await backendClient.createExperiment({
      id: experimentId,
      name: `${testNamespace}-exp`,
      datasetName: busy.name,
      projectName: project.name,
    });
    await backendClient.createExperimentItems(
      traceIds.map((traceId, i) => ({
        experimentId,
        datasetItemId: busyItemIds[i],
        traceId,
      })),
    );

    await backendClient.createOptimization({
      id: optimizationId,
      name: `${testNamespace}-opt`,
      datasetName: busy.name,
      projectName: project.name,
      objectiveName: OBJECTIVE,
      status: 'completed',
    });

    const ref: EnrichedDatasetsRef = {
      projectId: project.id,
      projectName: project.name,
      busy,
      quiet,
      experimentId,
      optimizationId,
      traceIds,
    };

    await testInfo.attach('opik.enrichedDatasets', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[enrichedDatasets fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiment and optimization before the dataset they reference, traces
      // last — nothing is removed from under a still-referencing parent.
      await safe(`experiment ${experimentId}`, () =>
        backendClient.deleteExperiment(experimentId),
      );
      await safe(`optimization ${optimizationId}`, () =>
        backendClient.deleteOptimization(optimizationId),
      );
      await safe(`dataset ${busyName}`, () => backendClient.deleteDataset(busy.id));
      await safe(`dataset ${quietName}`, () => backendClient.deleteDataset(quiet.id));
      await safe(`${traceIds.length} traces`, () => backendClient.deleteTraces(traceIds));
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
