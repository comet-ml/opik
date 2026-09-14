import { test as baseTest } from './weekly-metric-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/**
 * Items seeded into the shared dataset, and therefore records in each upload.
 *
 * Chosen with FILLER_BYTES so the upload splits into more batches than
 * `batch_upload_items` has workers: batching is driven by the SDK's 3.5MB
 * serialized-payload ceiling long before its 1000-item one, so 1200 records of
 * ~30KB is ~36MB, which is 11 batches against a pool of 8. Fewer batches than
 * workers would mean every batch went out in one round and the pool never
 * recycled a worker — the case a fan-out bug hides in.
 */
const SEED_ITEM_COUNT = 1200;

/** Filler added to each record's trace input, to drive the payload-size split. */
const FILLER_BYTES = 30_000;

/** The feedback score every uploaded record carries. */
export const BULK_SCORE_NAME = 'bulk_probe';

/**
 * Scores cycle 0.0, 0.1 … 0.9 by index, so the mean over 1200 records is
 * exactly 0.45. A count alone cannot see a batch dropped and another duplicated;
 * the mean can.
 */
export const BULK_EXPECTED_MEAN_SCORE = 0.45;

/** One arm of the comparison: the same records, uploaded at one worker count. */
export interface BulkUploadArmRef {
  experimentId: string;
  experimentName: string;
  /**
   * How the SDK split and dispatched the upload, as it reported it. `null` when
   * that could not be read — a caller asserting on the fan-out must fail rather
   * than be handed a number the bridge guessed.
   */
  batchCount: number | null;
  numThreads: number | null;
  /** One entry per uploaded record, in upload order. */
  items: Array<{ datasetItemId: string; score: number }>;
}

export interface BulkUploadedExperimentsRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  scoreName: string;
  itemCount: number;
  /** Uploaded with no `num_threads`, i.e. at whatever the SDK defaults to. */
  parallel: BulkUploadArmRef;
  /** The control: the identical records at `num_threads=1`. */
  sequential: BulkUploadArmRef;
}

export interface BulkUploadedExperimentsFixtures {
  bulkUploadedExperiments: BulkUploadedExperimentsRef;
}

/**
 * One dataset, uploaded twice through `Experiment.batch_upload_items` — once at
 * the SDK's default worker count and once sequentially — into two experiments.
 *
 * Both experiments share the dataset, so the two arms are comparable on the same
 * `dataset_item_id` set rather than on two independent seeds. Both also live in
 * the dataset's own project, which is not a choice: the bulk endpoint answers
 * 409 when an upload's `project_name` differs from the dataset's.
 *
 * A fixture rather than in-test seeding because two experiments and a dataset
 * have to be deleted whatever the test does, and a trailing cleanup step is
 * skipped the moment an earlier assertion throws — which is precisely when 2400
 * traces have been left behind.
 */
export const test = baseTest.extend<BulkUploadedExperimentsFixtures>({
  bulkUploadedExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-bulk-ds`;
    const parallelName = `${testNamespace}-bulk-par`;
    const sequentialName = `${testNamespace}-bulk-seq`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'batch_upload_items worker-count comparison',
    });
    await sdkClient.python.insertDatasetItems({
      project_name: project.name,
      dataset_name: datasetName,
      // Every item differs by index: Dataset.insert drops duplicates by content
      // hash, so identical items would seed fewer than SEED_ITEM_COUNT and
      // shrink the upload below the batch count the scenario needs.
      items: Array.from({ length: SEED_ITEM_COUNT }, (_, seq) => ({
        input: `bulk input ${seq}`,
        expected_output: `bulk output ${seq}`,
        seq,
      })),
    });

    const upload = async (
      experimentName: string,
      numThreads?: number,
    ): Promise<BulkUploadArmRef> => {
      const uploaded = await sdkClient.python.bulkUploadExperimentItems({
        project_name: project.name,
        dataset_name: datasetName,
        experiment_name: experimentName,
        score_name: BULK_SCORE_NAME,
        filler_bytes: FILLER_BYTES,
        ...(numThreads === undefined ? {} : { num_threads: numThreads }),
      });
      return {
        experimentId: uploaded.experiment_id,
        experimentName: uploaded.experiment_name,
        batchCount: uploaded.batch_count,
        numThreads: uploaded.num_threads,
        items: uploaded.items.map((item) => ({
          datasetItemId: item.dataset_item_id,
          score: item.score,
        })),
      };
    };

    // Sequentially, not concurrently: the two arms write the same dataset item
    // ids from one client each, and racing them would put ~72MB in flight at
    // once against an environment this suite shares.
    const parallel = await upload(parallelName);
    const sequential = await upload(sequentialName, 1);

    const ref: BulkUploadedExperimentsRef = {
      datasetId: dataset.id,
      datasetName,
      projectName: project.name,
      scoreName: BULK_SCORE_NAME,
      itemCount: SEED_ITEM_COUNT,
      parallel,
      sequential,
    };

    await testInfo.attach('opik.bulk-uploaded-experiments', {
      // The per-item score map is deliberately left out: 2400 entries say
      // nothing a reader of a failure needs, and the formula is in this file.
      body: JSON.stringify(
        {
          ...ref,
          parallel: { ...parallel, items: `${parallel.items.length} items` },
          sequential: { ...sequential, items: `${sequential.items.length} items` },
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use(ref);

    /** Experiments first (they reference the dataset), then the dataset. */
    if (!shouldLeaveArtifacts(testInfo)) {
      for (const arm of [parallel, sequential]) {
        try {
          await backendClient.deleteExperiment(arm.experimentId);
        } catch (err) {
          console.warn(
            `[bulkUploadedExperiments] delete experiment warning for ${arm.experimentName}:`,
            err,
          );
        }
      }
      try {
        await backendClient.deleteDataset(dataset.id);
      } catch (err) {
        console.warn(`[bulkUploadedExperiments] delete dataset warning for ${datasetName}:`, err);
      }
    }
  },
});

export { expect } from './weekly-metric-spans.fixture';
