import { test as baseTest } from './compare-project-mismatch.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * Enough rows to cross several page boundaries at a small page size, and few
 * enough to seed in one batch.
 *
 * 30 at size 7 gives 4 full pages and a remainder of 2, so the walk crosses a
 * boundary four times and ends on a short page — the two shapes an off-by-one
 * slice shows up in.
 */
export const RAW_ITEM_COUNT = 30;

export interface RawBranchExperimentRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  experimentId: string;
  experimentName: string;
  /** Dataset item ids, in the order they were minted (strictly increasing). */
  datasetItemIds: string[];
  /** When the last experiment item was written — the clock the raw window runs from. */
  writtenAtMs: number;
  /** How long after that write the read is still guaranteed to be un-aggregated. */
  rawBranchWindowMs: number;
}

export interface RawBranchExperimentFixtures {
  rawBranchExperiment: RawBranchExperimentRef;
}

/**
 * `experimentDenormalization.debounceDelay` is 1m and every write resets it.
 * Two thirds of that is the margin this fixture promises its callers — enough
 * for a handful of small paged reads, and short enough that a slow run fails
 * the window assertion rather than silently reading the aggregated branch.
 */
const RAW_BRANCH_WINDOW_MS = 40_000;

const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 1_000;

/**
 * A small un-aggregated experiment, for reads that must stay on the
 * `push_top_limit_raw` branch.
 *
 * `applyPushTopLimit` takes that branch only while `aggregated == 0` — before
 * the denormalization job has run against the experiment even once — and only
 * for a read with no filters, no search, and sorting by `id` or not at all.
 * `deep-paged-experiment.fixture.ts` seeds the same branch at 5,000 items to
 * exercise the shipped default page size; that costs about seven minutes, which
 * is the right price for THAT question and far too high for this one.
 *
 * The experiment is created `running` and its items are written LAST, over
 * dataset items and traces already proven queryable, so the debounce window
 * starts as late as possible and the caller gets the most of it.
 *
 * Dataset item ids are minted at strictly increasing milliseconds, so id order
 * is also seed order — which is what lets a caller assert an `id ASC` read
 * against a sequence it knows, rather than only against its own reverse.
 *
 * Teardown deletes the experiment, the dataset and the traces; none cascades
 * with the project.
 */
export const test = baseTest.extend<RawBranchExperimentFixtures>({
  rawBranchExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-raw-ds`;
    const experimentName = `${testNamespace}-raw-exp`;
    const experimentId = uuid7();

    // Strictly increasing instants, one millisecond apart, so the ids sort in
    // seed order. Backdated so the whole run sits in the past and no id can be
    // minted ahead of the clock.
    const base = Date.now() - RAW_ITEM_COUNT - 1_000;
    const rows = Array.from({ length: RAW_ITEM_COUNT }, (_, i) => ({
      datasetItemId: uuid7(new Date(base + i)),
      traceId: uuid7(new Date(base + i)),
      idx: i,
    }));

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'small un-aggregated experiment for raw-branch paged reads',
    });

    let seededTraces = false;
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: rows.map((row) => ({ id: row.datasetItemId, data: { idx: row.idx } })),
      });

      await backendClient.createTracesBatch({
        projectName: project.name,
        traces: rows.map((row) => ({
          id: row.traceId,
          name: `${testNamespace}-raw-trace-${row.idx}`,
          input: { idx: row.idx },
          output: { idx: row.idx },
        })),
      });
      seededTraces = true;

      await backendClient.createExperiment({
        id: experimentId,
        name: experimentName,
        datasetName,
        projectName: project.name,
        status: 'running',
      });

      // Written last, and the window measured from here — see the header.
      await backendClient.createExperimentItems(
        rows.map((row) => ({
          experimentId,
          datasetItemId: row.datasetItemId,
          traceId: row.traceId,
        })),
      );
      const writtenAtMs = Date.now();

      await waitForRows(backendClient, dataset.id, experimentId, RAW_ITEM_COUNT);

      const ref: RawBranchExperimentRef = {
        projectId: project.id,
        projectName: project.name,
        datasetId: dataset.id,
        datasetName,
        experimentId,
        experimentName,
        datasetItemIds: rows.map((row) => row.datasetItemId),
        writtenAtMs,
        rawBranchWindowMs: RAW_BRANCH_WINDOW_MS,
      };

      await testInfo.attach('opik.rawBranchExperiment', {
        body: JSON.stringify({ ...ref, datasetItemIds: `<${RAW_ITEM_COUNT} ids>` }, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[rawBranchExperiment fixture] delete warning for ${what}:`, err);
          }
        };
        await safe(`experiment ${experimentName}`, () =>
          backendClient.deleteExperiment(experimentId),
        );
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe(`${rows.length} traces`, () =>
            backendClient.deleteTraces(rows.map((row) => row.traceId)),
          );
        }
      }
    }
  },
});

/**
 * Block until the comparison reports exactly `expected` rows.
 *
 * Exactly, not at-least: a partition assertion over a half-landed seed would
 * compare a paged walk against an unpaged read that is also missing rows, and
 * agree.
 */
async function waitForRows(
  backendClient: {
    compareItemsPage: (args: {
      datasetId: string;
      experimentIds: string[];
      size?: number;
    }) => Promise<{ total: number }>;
  },
  datasetId: string,
  experimentId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    seen = (
      await backendClient.compareItemsPage({ datasetId, experimentIds: [experimentId], size: 1 })
    ).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[rawBranchExperiment fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

export { expect } from './compare-project-mismatch.fixture';
