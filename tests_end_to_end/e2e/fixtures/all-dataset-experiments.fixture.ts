import { test as baseTest } from './mixed-case-model-cost-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One seeded experiment and the type the `experiments` row was written under. */
export interface DatasetExperimentRef {
  id: string;
  name: string;
  type: 'regular' | 'trial';
  /** Dataset items this experiment reported a result against. */
  itemIds: string[];
}

export interface AllDatasetExperimentsRef {
  datasetId: string;
  datasetName: string;
  /** Every item stored in the dataset, in the order the API pages them out. */
  itemIds: string[];
  /** The two REGULAR experiments, each covering every item. */
  regular: DatasetExperimentRef[];
  /** The one TRIAL experiment, covering a single item. */
  trial: DatasetExperimentRef;
  /** The dataset's only version, which the seed's insert cut. */
  versionHash: string;
}

export interface AllDatasetExperimentsFixtures {
  allDatasetExperiments: AllDatasetExperimentsRef;
}

/**
 * Items per dataset. Chosen over the endpoint's own default page size (10) so
 * a read that silently fell back to the default answers a visibly short page,
 * and comfortably under the all-experiments cap (100) so the full row set fits
 * on one page and `total` can be compared against the collection length.
 */
const ITEM_COUNT = 20;

/**
 * A dataset whose experiments differ in TYPE, seeded for the all-experiments
 * mode `GET /datasets/{id}/items/experiments/items` grew in opik#9263.
 *
 * In that mode the caller does NOT name the experiments: the server resolves
 * them from the dataset, and `FIND_IDS_BY_DATASET_ID` filters `type = 'regular'`
 * so optimizer trial, mini-batch and mutation experiments are left out. A seed
 * of regular experiments alone cannot test that — every assertion would pass on
 * a build whose type filter had been dropped. So the shape is deliberately
 * asymmetric:
 *
 *  - TWO regular experiments, each with a result on EVERY item, so every
 *    returned row must carry exactly two results. The count being two rather
 *    than one is what makes a missing experiment visible per row instead of
 *    only in the id set.
 *  - ONE trial experiment with a result on the FIRST item only. One item rather
 *    than all of them on purpose: the trial must change neither `total` nor the
 *    row set when it is correctly excluded, so the row it touches is also
 *    covered by both regulars, and the ONLY way to see the trial leak in is the
 *    experiment ids on that row — three results where its siblings have two.
 *    A trial covering items the regulars do not would inflate `total` instead,
 *    which a weaker assertion would also catch and so would not discriminate.
 *
 * Seeded over REST rather than through `evaluate()` because the type is the
 * variable and the SDK's evaluate path only ever writes `regular` — the same
 * reason `experiment-type-datasets.fixture.ts` seeds that way.
 *
 * `versionHash` is read back rather than computed: `dataset_version` is 9263's
 * other new parameter, and a spec pinning the read to a version needs the hash
 * the backend actually stored. It is asserted present here, in the fixture,
 * because a read scoped to `undefined` would silently become an unscoped read
 * and every row assertion after it would still pass.
 *
 * Teardown deletes the experiments before the dataset they reference. Datasets
 * do not cascade with their project, and experiments do not cascade with the
 * dataset, so both are explicit; the traces go with the `project` fixture.
 */
export const test = baseTest.extend<AllDatasetExperimentsFixtures>({
  allDatasetExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-all-exp-ds`;
    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'all-experiments compare read (opik#9263)',
      items: Array.from({ length: ITEM_COUNT }, (_, i) => ({
        input: `all-exp question ${i + 1}`,
        expected_output: `all-exp answer ${i + 1}`,
      })),
    });

    // The full page-out, not `getDatasetItems`: that one takes the endpoint's
    // default page (10) and would quietly leave half the items unlinked, so
    // every experiment would cover 10 of 20 and `total` would be 10.
    const itemIds = await backendClient.listDatasetItemIds(created.id);
    if (itemIds.length !== ITEM_COUNT) {
      throw new Error(
        `[allDatasetExperiments fixture] dataset '${datasetName}' stored ` +
          `${itemIds.length} items, expected ${ITEM_COUNT}`,
      );
    }

    const versions = await backendClient.getDatasetVersions(created.id);
    const latest = versions.find((v) => v.isLatest);
    const versionHash = latest?.versionHash;
    if (versions.length !== 1 || !versionHash) {
      throw new Error(
        `[allDatasetExperiments fixture] dataset '${datasetName}' has ` +
          `${versions.length} version(s) and latest hash '${versionHash ?? 'none'}' — ` +
          'the seed is one insert, so exactly one version carrying a hash is expected',
      );
    }

    const shapes: Array<{ type: 'regular' | 'trial'; items: string[] }> = [
      { type: 'regular', items: itemIds },
      { type: 'regular', items: itemIds },
      { type: 'trial', items: itemIds.slice(0, 1) },
    ];

    const experiments: DatasetExperimentRef[] = [];
    for (const [index, shape] of shapes.entries()) {
      const experimentId = uuid7();
      const experimentName = `${testNamespace}-all-exp-${shape.type}-${index + 1}`;
      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        type: shape.type,
      });

      // One trace per item this experiment covers. The compare read sources a
      // row's input/output from the trace an experiment item names, so an
      // experiment item without one would come back as a well-formed row with
      // empty cells.
      const traceIds = shape.items.map(() => uuid7());
      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: traceIds.map((id, i) => ({
          id,
          name: `${experimentName}-trace-${i + 1}`,
          input: { question: `all-exp question ${i + 1}` },
          output: { answer: `all-exp answer ${i + 1}` },
        })),
      });
      await backendClient.createExperimentItems(
        shape.items.map((datasetItemId, i) => ({
          experimentId,
          datasetItemId,
          traceId: traceIds[i],
        })),
      );

      experiments.push({
        id: experimentId,
        name: experimentName,
        type: shape.type,
        itemIds: shape.items,
      });
    }

    const regular = experiments.filter((e) => e.type === 'regular');
    const trial = experiments.find((e) => e.type === 'trial');
    if (regular.length !== 2 || !trial) {
      throw new Error(
        `[allDatasetExperiments fixture] seeded ${regular.length} regular experiment(s) ` +
          `and ${trial ? 1 : 0} trial, expected 2 and 1`,
      );
    }

    const ref: AllDatasetExperimentsRef = {
      datasetId: created.id,
      datasetName,
      itemIds,
      regular,
      trial,
      versionHash,
    };

    try {
      await testInfo.attach('opik.allDatasetExperiments', {
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
            console.warn(`[allDatasetExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        for (const experiment of experiments) {
          await safe(`experiment ${experiment.id}`, () =>
            backendClient.deleteExperiment(experiment.id),
          );
        }
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(created.id));
      }
    }
  },
});

export { expect } from './mixed-case-model-cost-spans.fixture';
