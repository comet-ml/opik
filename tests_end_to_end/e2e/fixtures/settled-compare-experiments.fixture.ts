import { expect } from '@playwright/test';
import { test as baseTest } from './multi-annotator-queue.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * How many dataset items the comparison holds.
 *
 * 30 at page size 7 gives four full pages and a remainder of two, so a paged
 * walk crosses a boundary four times and ends short — the two shapes a slice
 * bug shows up in. Small enough to seed in one batch per entity.
 */
export const SETTLED_ITEM_COUNT = 30;

/**
 * The marker one experiment's trace writes for one dataset item: the pair
 * `(experiment, idx)` as a single string.
 *
 * A single string because of where it has to be READ: the compare grid's output
 * column is `output.output`, one cell of text per band, so a payload that
 * encoded the pair as two JSON keys would not be assertable in the UI at all.
 * Exported so the fixture and its spec cannot drift on the spelling.
 */
export const compareMarker = (experimentNumber: number, idx: number): string =>
  `e${experimentNumber}-i${idx}`;

/** One experiment in the comparison, and the number its payloads self-report. */
export interface SettledCompareExperimentRef {
  id: string;
  name: string;
  /** 1 or 2 — written into every one of this experiment's trace payloads. */
  number: number;
}

export interface SettledCompareExperimentsRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  /** The two experiments, in the order the compare bands render them. */
  experiments: SettledCompareExperimentRef[];
  /** Dataset item ids, in the order they were minted (strictly increasing). */
  datasetItemIds: string[];
  /** `datasetItemId` -> the `idx` its `data` carries. */
  idxByItemId: Record<string, number>;
  /** When the last experiment item was written — the clock the debounce runs from. */
  writtenAtMs: number;
  /** How long the fixture waited, quietly, after that write. */
  settledForMs: number;
}

export interface SettledCompareExperimentsFixtures {
  settledCompareExperiments: SettledCompareExperimentsRef;
}

/**
 * How long to wait, with no further writes, before handing the comparison over.
 *
 * `experimentDenormalization.debounceDelay` is 1m and every write to an
 * experiment resets it; the flush job then scans every 5s and the subscriber
 * writes `experiment_item_aggregates`. 100s covers the debounce, a scan and the
 * write with room to spare, and every millisecond of it is dead time — hence
 * the comment in the header about what this fixture can and cannot promise.
 */
const SETTLE_MS = 100_000;

const QUERYABLE_TIMEOUT_MS = 120_000;

/**
 * Thirty dataset items and two experiments over them, left quiet long enough
 * for the denormalization job to have run, with every trace payload encoding
 * its own (experiment, item index).
 *
 * The seed for the aggregated half of opik#9402 (OPIK-8274), which stopped
 * carrying `input`/`output`/`metadata` through the aggregation and joins them
 * once per page instead. A mis-join returns the right row ids, the right total
 * and another row's payloads — so the only way to catch it is for every payload
 * to say which row and which experiment it belongs to, which is what `idx` and
 * `experiment` in each trace's input, output and metadata are for.
 *
 * **What this fixture can promise, and what it cannot.** It can promise that
 * `SETTLE_MS` elapsed after the last write with nothing else touching the
 * experiments. It CANNOT promise that the aggregated branch served the read:
 * `applyPushTopLimit` picks between `late_materialize` and
 * `late_materialize_raw` on `AggregatedExperimentCounts`, and no field of any
 * API response reports which one was chosen — `GET /experiments/{id}`, the
 * experiments listing and the compare read itself are byte-identical before and
 * after aggregation (measured, not assumed). So on a deployment whose
 * denormalization job is disabled or wedged, a spec over this fixture
 * re-exercises the raw branch instead. That is a weaker premise than
 * `rawBranchExperiment`'s, which can assert its window has NOT elapsed, and it
 * is stated here rather than hidden because the join assertion is worth making
 * on whichever branch answers.
 *
 * Both experiments are written over dataset items and traces already proven
 * queryable, and their experiment-item links are the last writes — so the
 * settle window starts as late as possible and covers both.
 *
 * Dataset-item ids are minted at strictly increasing milliseconds, so id order
 * is also seed order and `idx` order. The compare read's default order is
 * NEITHER of those (measured: it returns rows in an order of its own), so a
 * caller must pair each row by its `data.idx` rather than by position — which
 * the ids being ordered does not change, and which is why this fixture ships
 * `idxByItemId` instead of relying on an index.
 *
 * Teardown deletes the experiments, the dataset and the traces; none cascades
 * with the project.
 */
export const test = baseTest.extend<SettledCompareExperimentsFixtures>({
  settledCompareExperiments: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-settled-ds`;

    // Strictly increasing instants, one millisecond apart, backdated so the
    // whole run sits in the past and no id is minted ahead of the clock.
    const base = Date.now() - SETTLED_ITEM_COUNT - 1_000;
    const items = Array.from({ length: SETTLED_ITEM_COUNT }, (_, i) => ({
      datasetItemId: uuid7(new Date(base + i)),
      idx: i,
    }));

    const experiments: SettledCompareExperimentRef[] = [1, 2].map((number) => ({
      id: uuid7(),
      name: `${testNamespace}-settled-exp-${number}`,
      number,
    }));

    /** `experimentNumber` -> the trace seeded for each item index. */
    const traceIdsByExperiment = new Map<number, string[]>(
      experiments.map((e) => [
        e.number,
        items.map(() => uuid7(new Date(base + SETTLED_ITEM_COUNT + e.number))),
      ]),
    );
    const allTraceIds = [...traceIdsByExperiment.values()].flat();

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'two settled experiments whose payloads self-report their row and experiment',
    });

    let seededTraces = false;
    const createdExperiments: string[] = [];
    try {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: items.map((item) => ({ id: item.datasetItemId, data: { idx: item.idx } })),
      });

      for (const experiment of experiments) {
        const traceIds = traceIdsByExperiment.get(experiment.number) as string[];
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: items.map((item, i) => ({
            id: traceIds[i],
            name: `${testNamespace}-e${experiment.number}-i${item.idx}`,
            // All three payload fields carry the SAME marker, because 9402
            // materializes all three in one join: a mis-join moves them
            // together, and a read that lost only one of them is a different
            // defect that only a per-field assertion can see.
            //
            // `output` is shaped `{ output: … }` so the compare grid has an
            // `output.output` column to render — that cell is the only place
            // the UI shows a payload without opening a panel, and a payload
            // nested anywhere else would be API-assertable only.
            input: { marker: compareMarker(experiment.number, item.idx) },
            output: { output: compareMarker(experiment.number, item.idx) },
            metadata: { marker: compareMarker(experiment.number, item.idx) },
          })),
        });
      }
      seededTraces = true;

      // Both proven queryable before the experiment links are written, so the
      // settle window below is spent settling rather than waiting on ingestion.
      await expect
        .poll(
          async () =>
            (await backendClient.searchTraceIdsPage({ projectId: project.id, size: 200 })).total,
          {
            message: 'every seeded trace must be queryable before the experiments are linked',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(allTraceIds.length);

      for (const experiment of experiments) {
        await backendClient.createExperiment({
          id: experiment.id,
          name: experiment.name,
          datasetName,
          projectName: project.name,
        });
        createdExperiments.push(experiment.id);
        const traceIds = traceIdsByExperiment.get(experiment.number) as string[];
        await backendClient.createExperimentItems(
          items.map((item, i) => ({
            experimentId: experiment.id,
            datasetItemId: item.datasetItemId,
            traceId: traceIds[i],
          })),
        );
      }
      const writtenAtMs = Date.now();

      // Exactly the seeded population, before the clock starts: a comparison
      // that has only half landed still answers a well-formed page, so a
      // fixture that waited on anything looser would hand over a seed whose
      // own size it did not know.
      await expect
        .poll(
          async () =>
            (
              await backendClient.compareItemsPage({
                datasetId: dataset.id,
                experimentIds: experiments.map((e) => e.id),
                size: 1,
              })
            ).total,
          {
            message: 'the comparison must report every seeded item before the settle wait',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [1_000, 2_000, 5_000],
          },
        )
        .toBe(SETTLED_ITEM_COUNT);

      // The quiet period. A plain wait and not a poll, deliberately: polling
      // the comparison here would be a READ, which is harmless, but polling
      // anything that WRITES would reset the debounce it is waiting out. There
      // is nothing to poll for either — see the header on why the branch is
      // unobservable.
      const settleStartedAt = Date.now();
      await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
      const settledForMs = Date.now() - settleStartedAt;

      const ref: SettledCompareExperimentsRef = {
        projectId: project.id,
        projectName: project.name,
        datasetId: dataset.id,
        datasetName,
        experiments,
        datasetItemIds: items.map((item) => item.datasetItemId),
        idxByItemId: Object.fromEntries(items.map((item) => [item.datasetItemId, item.idx])),
        writtenAtMs,
        settledForMs,
      };

      await testInfo.attach('opik.settledCompareExperiments', {
        body: JSON.stringify(
          { ...ref, datasetItemIds: `<${SETTLED_ITEM_COUNT} ids>`, idxByItemId: '<30 entries>' },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            console.warn(`[settledCompareExperiments fixture] delete warning for ${what}:`, err);
          }
        };
        for (const experimentId of createdExperiments) {
          await safe(`experiment ${experimentId}`, () =>
            backendClient.deleteExperiment(experimentId),
          );
        }
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          await safe(`${allTraceIds.length} traces`, () =>
            backendClient.deleteTraces(allTraceIds),
          );
        }
      }
    }
  },
});

export { expect } from './multi-annotator-queue.fixture';
