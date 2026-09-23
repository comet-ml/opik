import { test as baseTest } from './boundary-threads.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient, type TraceBatchSeed } from '../core/backend';

export interface ExperimentItemReadRef {
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  projectName: string;
  /** How many dataset items — and so how many experiment items — were written. */
  itemCount: number;
}

export interface ExperimentItemReadFixtures {
  experimentItemRead: ExperimentItemReadRef;
}

/**
 * 250 items.
 *
 * NOT sized to cross the SDK's default page size (2,000). Crossing it would
 * need >2,000 dataset items, >2,000 traces and >2,000 experiment items —
 * ~6,300 writes — and a shared cloud workspace answers 429 ("exceeded the rate
 * limit for this user in this workspace") well before that lands. Measured:
 * a 2,100-row seed failed on its dataset-item writes on all three attempts.
 *
 * So the paging this spec exercises is driven by an explicit `page_size`
 * instead. That reaches the same code — OPIK-8274's wave loop, its page-count
 * arithmetic and its concurrent assembly are the same whatever the page size
 * is — but it does NOT exercise the default value multi-page. A full read at
 * the shipped default stays on the release's manual list.
 *
 * 250 matches `exportComparison`, which is the largest seed this estate is
 * known to get past the rate limit. Deliberately not a multiple of the small
 * page sizes below, so a reader that dropped a whole page cannot finish on a
 * clean boundary.
 */
const ITEM_COUNT = 250;

/** Dataset items per write. The endpoint caps at 1,000; this is rate-limit paced. */
const DATASET_BATCH_SIZE = 250;
/** Traces per `POST /v1/private/traces/batch` — the endpoint caps at 1,000. */
const TRACE_BATCH_SIZE = 250;
/** Experiment items per write. The endpoint caps at 1,000. */
const EXPERIMENT_ITEM_BATCH_SIZE = 250;

/** How long the seed may take to become fully queryable. */
const QUERYABLE_TIMEOUT_MS = 300_000;
const QUERYABLE_POLL_MS = 2_000;

const chunk = <T>(values: T[], size: number): T[][] =>
  Array.from({ length: Math.ceil(values.length / size) }, (_, i) =>
    values.slice(i * size, (i + 1) * size),
  );

/**
 * Block until the experiment reports exactly `expected` rows.
 *
 * Exactly, not at-least. A spec whose subject is "the read returns every row,
 * once, in order" cannot start against a seed that only half landed: it would
 * compare four reads of a 1,400-row experiment, find them consistent, and pass
 * having never crossed the page boundary it exists to cross.
 */
async function waitForExperimentRows(
  backendClient: BackendClient,
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    // size 1: the total is in the envelope, so there is no reason to transfer
    // rows in order to count them.
    seen = (
      await backendClient.compareItemsPage({
        datasetId,
        experimentIds: [experimentId],
        size: 1,
      })
    ).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[experimentItemRead fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

/**
 * An experiment big enough that `Experiment.get_items()` has to page.
 *
 * Seeded through REST rather than the bridge's `evaluate`/`compare-seed`
 * routes, for the reason `exportComparison` gives: those run a real
 * `evaluate()` with `task_threads=1`, which would be 2,100 sequential task runs
 * inside a single HTTP call. These rows exist to be counted, ordered and
 * de-duplicated — they carry no scores and no LLM output — so the dataset
 * items, the traces and the experiment items are written directly, in batches.
 *
 * Every dataset item carries a monotonic `idx`, which is what lets a reader be
 * checked for gaps, duplicates and reordering without the spec having to hold
 * 2,100 rows of expected content.
 *
 * Teardown deletes the experiment and then the dataset — neither cascades with
 * the project — and the traces explicitly, because deleting a project does not
 * take its traces with it.
 */
export const test = baseTest.extend<ExperimentItemReadFixtures>({
  experimentItemRead: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-expitems-ds`;
    const experimentName = `${testNamespace}-expitems-exp`;
    const experimentId = uuid7();

    const itemIds = Array.from({ length: ITEM_COUNT }, () => uuid7());
    const traceIds = Array.from({ length: ITEM_COUNT }, () => uuid7());

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'paged experiment-item read via the Python SDK',
    });

    let seededTraces = false;
    try {
      for (const batch of chunk(
        itemIds.map((id, i) => ({
          id,
          data: { idx: i, input: `row-${String(i).padStart(4, '0')}` },
        })),
        DATASET_BATCH_SIZE,
      )) {
        await backendClient.writeDatasetItemsBatch({ datasetId: dataset.id, items: batch });
      }

      const traces: TraceBatchSeed[] = traceIds.map((id, i) => ({
        id,
        name: `${testNamespace}-expitems-trace-${String(i).padStart(4, '0')}`,
        input: { input: `row-${String(i).padStart(4, '0')}` },
        output: { output: `answer-${i}` },
      }));
      for (const batch of chunk(traces, TRACE_BATCH_SIZE)) {
        await backendClient.createTracesBatch({ projectName: project.name, traces: batch });
        seededTraces = true;
      }

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
      });

      for (const batch of chunk(
        itemIds.map((datasetItemId, i) => ({
          experimentId,
          datasetItemId,
          traceId: traceIds[i],
        })),
        EXPERIMENT_ITEM_BATCH_SIZE,
      )) {
        await backendClient.createExperimentItems(batch);
      }

      await waitForExperimentRows(backendClient, dataset.id, experimentId, ITEM_COUNT);

      const ref: ExperimentItemReadRef = {
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        projectName: project.name,
        itemCount: ITEM_COUNT,
      };

      await testInfo.attach('opik.experimentItemRead', {
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
            console.warn(`[experimentItemRead fixture] delete warning for ${what}:`, err);
          }
        };
        // The experiment before the dataset it references.
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          for (const batch of chunk(traceIds, TRACE_BATCH_SIZE)) {
            await safe(`${batch.length} traces`, () => backendClient.deleteTraces(batch));
          }
        }
      }
    }
  },
});

export { expect } from './boundary-threads.fixture';
