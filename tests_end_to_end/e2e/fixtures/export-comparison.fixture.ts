import { test as baseTest, expect } from './project-scoped-dashboard.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient, type TraceBatchSeed } from '../core/backend';

/** One seeded dataset item, as the spec needs to address it. */
export interface ExportComparisonItem {
  id: string;
  /** Unique and lexicographically ordered (`row-000` … `row-249`). */
  input: string;
  bucket: 'even' | 'odd';
  /** Long on exactly one item (see `longItemId`), short on every other. */
  detail: string;
  /** True for the slice carrying the search marker. */
  marked: boolean;
}

export interface ExportComparisonExperiment {
  id: string;
  name: string;
}

export interface ExportComparisonRef {
  datasetId: string;
  datasetName: string;
  projectName: string;
  experiments: ExportComparisonExperiment[];
  items: ExportComparisonItem[];
  /** Every seeded row — what an unscoped export has to cover. */
  rowCount: number;
  /** Items whose `bucket` is `even`. */
  evenInputs: string[];
  /** Items carrying `searchMarker`, across both buckets. */
  markedInputs: string[];
  /** Items that are BOTH `even` and marked — the filter∩search answer. */
  evenMarkedInputs: string[];
  searchMarker: string;
  /** The >1,000-character value stored on one item's `detail` field. */
  longDetail: string;
  longItemId: string;
  longItemInput: string;
}

export interface ExportComparisonFixtures {
  exportComparison: ExportComparisonRef;
}

/**
 * 250 rows.
 *
 * Comfortably more than one screen and more than the table's own page, so the
 * failure this spec exists to catch — a file holding the rows on screen rather
 * than the whole result set — lands on a count nothing else produces. It also
 * sits well under `EXPORT_ROW_LIMIT` (2,000 in
 * `getAllCompareExperimentsItems.ts`), so the export is offered rather than
 * refused; the over-the-cap branch is deliberately out of scope here, since
 * seeding past that cap costs far more than the assertion is worth.
 *
 * Odd multiple of the table page on purpose: a count that divided evenly into
 * whatever the read chunks by would let a reader that dropped or duplicated a
 * whole chunk still finish on a clean boundary.
 */
const ROW_COUNT = 250;

/**
 * The search marker sits on a 10-row slice, 5 of which are `even`. Every
 * constraint in the filter+search test therefore admits a different count —
 * 125 filtered, 10 searched, 5 both — so an export that dropped either
 * constraint lands on a number no other combination produces.
 */
const MARKED_FROM = 100;
const MARKED_TO = 110;
const SEARCH_MARKER = 'ZEBRAFINCH';

/** Which row carries the long value. Arbitrary, but not the first or last page. */
const LONG_ITEM_INDEX = 137;

/**
 * Comfortably past the server's display truncation, so the table's read and
 * the export's read of the same cell genuinely differ.
 *
 * The backend cuts every `data` value at `responseFormatting.truncationSize`
 * when the caller asks for `truncate=true`, which the grid does and the export
 * deliberately does not. That limit defaults to 10,001 characters, so a value
 * of a few hundred — the obvious size to reach for — would come back whole on
 * both reads and make the untruncated-export assertion unable to fail. No spec
 * here hardcodes the limit; this constant only has to sit above it.
 *
 * Ends in a sentinel so a failure says whether the tail was lost rather than
 * only that two lengths differ.
 */
const LONG_DETAIL = `${'the quick brown fox jumps over the lazy dog. '.repeat(280)}-LONGEND`;

/** Traces per `POST /v1/private/traces/batch` — the endpoint caps at 1,000. */
const TRACE_BATCH_SIZE = 250;

/** How long a just-written comparison may take to become fully queryable. */
const QUERYABLE_TIMEOUT_MS = 120_000;
const QUERYABLE_POLL_MS = 1_000;

const chunk = <T>(values: T[], size: number): T[][] =>
  Array.from({ length: Math.ceil(values.length / size) }, (_, i) =>
    values.slice(i * size, (i + 1) * size),
  );

/**
 * Block until the comparison read reports exactly `expected` rows.
 *
 * Both halves of "exactly" matter. Ingestion is eventually consistent, so a
 * read taken straight after the write legitimately sees fewer; and a spec whose
 * subject is "the export covers every page" cannot open a browser against a
 * seed that only half landed — it would assert a row count that was never the
 * seed's, and pass.
 */
async function waitForComparisonRows(
  backendClient: BackendClient,
  datasetId: string,
  experimentIds: string[],
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    // size 1: the total is in the envelope, so there is no reason to transfer
    // rows to count them.
    seen = (await backendClient.compareItemsPage({ datasetId, experimentIds, size: 1 })).total;
    if (seen === expected) return;
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[exportComparison fixture] dataset ${datasetId} reported ${seen} comparison rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms`,
  );
}

/**
 * A comparison large enough to page, seeded through REST rather than through
 * the SDK bridge's `compare-seed`.
 *
 * `compare-seed` runs a real `evaluate()` per experiment with `task_threads=1`,
 * which is the right shape for a three-item comparison and the wrong one for
 * 250 × 2: it would cost 500 sequential task runs inside one HTTP call. The
 * rows this fixture needs carry no scores and no LLM output — they exist to be
 * counted, filtered and exported — so the dataset items, the traces and the
 * experiment items are written directly, in batches.
 *
 * Teardown deletes both experiments and then the dataset; neither cascades with
 * the project. The traces go with the `project` fixture, as they do for
 * `agedExperiment`.
 */
export const test = baseTest.extend<ExportComparisonFixtures>({
  exportComparison: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const datasetName = `${testNamespace}-export-ds`;
    const experimentNames = [`${testNamespace}-export-expA`, `${testNamespace}-export-expB`];

    const items: ExportComparisonItem[] = Array.from({ length: ROW_COUNT }, (_, i) => {
      const marked = i >= MARKED_FROM && i < MARKED_TO;
      return {
        id: uuid7(),
        input: `row-${String(i).padStart(3, '0')}${marked ? `-${SEARCH_MARKER}` : ''}`,
        bucket: i % 2 === 0 ? ('even' as const) : ('odd' as const),
        detail: i === LONG_ITEM_INDEX ? LONG_DETAIL : `detail-${i}`,
        marked,
      };
    });

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'paged comparison for the browser export',
    });

    for (const batch of chunk(items, TRACE_BATCH_SIZE)) {
      await backendClient.writeDatasetItemsBatch({
        datasetId: dataset.id,
        items: batch.map((item) => ({
          id: item.id,
          data: {
            input: item.input,
            expected_output: `answer-${item.input}`,
            bucket: item.bucket,
            detail: item.detail,
          },
        })),
      });
    }

    const experiments: ExportComparisonExperiment[] = [];
    for (const name of experimentNames) {
      const experimentId = uuid7();
      await backendClient.createExperiment({
        id: experimentId,
        name,
        datasetName,
        projectName: project.name,
      });

      const traces: TraceBatchSeed[] = items.map((item) => ({
        id: uuid7(),
        name: `${name}-${item.input}`,
        input: { input: item.input },
        output: { output: `${name}::${item.input}` },
      }));

      for (const batch of chunk(traces, TRACE_BATCH_SIZE)) {
        await backendClient.createTracesBatch({ projectName: project.name, traces: batch });
      }

      for (const batch of chunk(
        items.map((item, i) => ({
          experimentId,
          datasetItemId: item.id,
          traceId: traces[i].id,
        })),
        TRACE_BATCH_SIZE,
      )) {
        await backendClient.createExperimentItems(batch);
      }

      experiments.push({ id: experimentId, name });
    }

    const experimentIds = experiments.map((e) => e.id);

    await waitForComparisonRows(backendClient, dataset.id, experimentIds, ROW_COUNT);

    // The discriminating property the filter+search test leans on is a property
    // of the STORED rows, not of this file's arithmetic. Confirm it server-side
    // before any browser opens: a seed that wrote the wrong bucket split would
    // otherwise produce a UI assertion that cannot fail.
    const stored = await backendClient.listDatasetItemsWithData(dataset.id);
    const storedEven = stored.filter((row) => row.data.bucket === 'even');
    expect(stored, 'stored dataset items').toHaveLength(ROW_COUNT);
    expect(storedEven, 'stored items in the `even` bucket').toHaveLength(ROW_COUNT / 2);

    // Same for the long value: the untruncated-export assertion only means
    // something if the display read of that cell really is cut short. Prove it
    // against the grid's own read rather than trusting that LONG_DETAIL sits
    // above whatever `responseFormatting.truncationSize` this deployment runs.
    const displayRead = await backendClient.compareItemsPage({
      datasetId: dataset.id,
      experimentIds,
      size: ROW_COUNT,
      truncate: true,
      search: items[LONG_ITEM_INDEX].input,
    });
    const longRow = displayRead.rows.find((row) => row.id === items[LONG_ITEM_INDEX].id);
    expect(longRow, `the long-value row (${items[LONG_ITEM_INDEX].input}) in the grid's read`)
      .toBeDefined();
    expect(
      String(longRow!.data.detail).length,
      "the grid's truncated read of the long value",
    ).toBeLessThan(LONG_DETAIL.length);

    const ref: ExportComparisonRef = {
      datasetId: dataset.id,
      datasetName,
      projectName: project.name,
      experiments,
      items,
      rowCount: ROW_COUNT,
      evenInputs: items.filter((i) => i.bucket === 'even').map((i) => i.input),
      markedInputs: items.filter((i) => i.marked).map((i) => i.input),
      evenMarkedInputs: items.filter((i) => i.marked && i.bucket === 'even').map((i) => i.input),
      searchMarker: SEARCH_MARKER,
      longDetail: LONG_DETAIL,
      longItemId: items[LONG_ITEM_INDEX].id,
      longItemInput: items[LONG_ITEM_INDEX].input,
    };

    await testInfo.attach('opik.exportComparison', {
      body: JSON.stringify({ ...ref, items: ref.items.length }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[exportComparison fixture] delete warning for ${what}:`, err);
        }
      };
      // Experiments before the dataset they reference.
      for (const experiment of experiments) {
        await safe(`experiment ${experiment.name}`, () =>
          backendClient.deleteExperiment(experiment.id),
        );
      }
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect };
