import { test as baseTest } from './feedback-score-reasons.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  isRateLimitedError,
  uuid7,
  type BackendClient,
  type TraceBatchSeed,
} from '../core/backend';

/** The seeded pairing, indexed by the `idx` the seed wrote onto each dataset item. */
export interface DeepPagedItemSeed {
  idx: number;
  datasetItemId: string;
  traceId: string;
}

export interface DeepPagedExperimentRef {
  projectId: string;
  projectName: string;
  datasetId: string;
  datasetName: string;
  /**
   * The experiment the fixture waited on. Its only job is to prove the dataset
   * items and the traces are queryable before the second experiment is written
   * — by the time that wait returns, this one has almost certainly been
   * denormalized, so it is NOT the one to read for the rewritten branch.
   */
  settledExperimentId: string;
  settledExperimentName: string;
  /**
   * The experiment whose items were written last, over dataset items and traces
   * that had already landed. Read THIS one, immediately: while it is
   * un-aggregated it is the only one of the two that reaches
   * `push_top_limit_raw`.
   */
  freshExperimentId: string;
  freshExperimentName: string;
  /**
   * `Date.now()` at the moment the last experiment item was written to
   * `freshExperimentId` — the instant the denormalization debounce starts
   * counting from, and so the clock a caller must measure its read against.
   */
  freshWrittenAtMs: number;
  /**
   * How long after `freshWrittenAtMs` a read is still guaranteed to find the
   * experiment un-aggregated. See `RAW_BRANCH_WINDOW_MS`.
   */
  rawBranchWindowMs: number;
  itemCount: number;
  seeded: DeepPagedItemSeed[];
}

export interface DeepPagedExperimentFixtures {
  deepPagedExperiment: DeepPagedExperimentRef;
}

/**
 * 5,000 items — enough that a read at the SHIPPED DEFAULT page size (2,000)
 * spans three pages plus a short one.
 *
 * That number is the whole reason this fixture exists next to
 * `experimentItemRead`, which seeds 250 and says in its own header that the
 * default page size is out of scope because "a seed large enough to make the
 * shipped default page is ~6,300 writes and a shared cloud workspace answers
 * 429 long before that lands". Measured on staging for 2.2.82: 5,000 does get
 * through, with the 250-row batches, the inter-batch pace and the 429 backoff
 * below — but it costs ~7 minutes of mostly waiting, which is why this seed is
 * `@t3-nightly` and must never be pulled into a fast suite.
 *
 * Not a multiple of the page sizes the spec reads at, in either direction: at
 * 2,000 the last page is short (1,000) and at 700 it is short by a different
 * remainder (100), so a reader that dropped a whole page cannot finish on a
 * clean boundary at either size.
 */
const ITEM_COUNT = 5_000;

/**
 * Rows per write, for all three populations. The endpoints cap at 1,000; 250 is
 * what the estate's largest seeds already use and what was measured to get
 * 5,000 rows past the workspace rate limiter.
 */
const BATCH_SIZE = 250;

/**
 * Pause between consecutive writes. Rate limiting here is a budget over time,
 * not a per-request verdict, so the cheapest way past it is to go slower rather
 * than to retry harder — 20 batches at this pace cost 8s of waiting and avoid
 * the 429s that would otherwise cost far more in backoff.
 */
const WRITE_PACE_MS = 400;

/** Stand-off before re-attempting a write the rate limiter refused. */
const RATE_LIMIT_BACKOFF_MS = [2_000, 5_000, 10_000, 20_000, 40_000];

/** How long the first experiment may take to become fully queryable. */
const QUERYABLE_TIMEOUT_MS = 600_000;
const QUERYABLE_POLL_MS = 3_000;

/**
 * The same wait for the SECOND experiment, on a much shorter leash and polled
 * much harder.
 *
 * Its dataset items and traces are already proven queryable by the time its
 * links are written, so the only thing outstanding is the experiment-item insert
 * itself — measured on staging as readable 0.7s after the last write. A budget
 * rather than a single read because that is a race, and losing it would fail the
 * spec on ingestion lag while reporting a paging defect; short because every
 * millisecond spent here is a millisecond of the denormalization window that the
 * paged reads no longer have. Overrunning it is a seed failure, not a product
 * one: at that point the fixture cannot build the state the spec needs.
 */
const FRESH_QUERYABLE_TIMEOUT_MS = 15_000;
const FRESH_QUERYABLE_POLL_MS = 250;

/**
 * How long after the last experiment-item write the fresh experiment is still
 * guaranteed un-aggregated — and therefore how long `push_top_limit_raw` is
 * reachable.
 *
 * `experimentDenormalization.debounceDelay` is 1m and every write resets it, so
 * nothing is published to the denormalization stream until 60s after the last
 * write (the flush job then adds up to its own 5s interval, and the aggregation
 * itself takes longer still). 45s is that 60s floor with margin, because the
 * consequence of being wrong is not a failure — it is a read served by the
 * aggregated branch while the spec claims to have exercised the raw one.
 *
 * The spec asserts its in-window reads finished inside this budget. That
 * assertion is the only thing standing between "we exercised the rewritten
 * branch" and "we exercised something and assumed": nothing in the response
 * identifies which branch served it.
 */
const RAW_BRANCH_WINDOW_MS = 45_000;

const chunk = <T>(values: T[], size: number): T[][] =>
  Array.from({ length: Math.ceil(values.length / size) }, (_, i) =>
    values.slice(i * size, (i + 1) * size),
  );

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

/**
 * Run one seed write, standing off and retrying only when the rate limiter
 * refused it.
 *
 * Only 429: any other error is a real one and must surface immediately, because
 * a seed that half landed is the worst possible input to a spec whose subject is
 * "every row comes back exactly once" — it would page a short experiment, find
 * it self-consistent, and pass having never crossed the boundary it exists to
 * cross. A squeeze that outlasts the whole backoff still throws, since at that
 * point it is not a burst and the seed genuinely cannot be built.
 */
async function writeWithBackoff(what: string, write: () => Promise<void>): Promise<void> {
  for (let attempt = 0; ; attempt++) {
    try {
      await write();
      return;
    } catch (err) {
      if (!isRateLimitedError(err) || attempt >= RATE_LIMIT_BACKOFF_MS.length) {
        throw new Error(
          `[deepPagedExperiment fixture] ${what} failed after ${attempt + 1} attempt(s): ` +
            `${err instanceof Error ? err.message : String(err)}`,
          { cause: err },
        );
      }
      await sleep(RATE_LIMIT_BACKOFF_MS[attempt]);
    }
  }
}

/** Write every batch in order, paced, with the 429 backoff around each. */
async function writeBatches<T>(
  what: string,
  batches: T[][],
  write: (batch: T[]) => Promise<void>,
): Promise<void> {
  for (const [i, batch] of batches.entries()) {
    await writeWithBackoff(`${what} batch ${i + 1}/${batches.length}`, () => write(batch));
    if (i < batches.length - 1) await sleep(WRITE_PACE_MS);
  }
}

/**
 * Block until the experiment reports exactly `expected` rows.
 *
 * Exactly, not at-least, and for the reason `experimentItemRead` gives: a spec
 * whose subject is a partition cannot start against a seed that only half
 * landed. Rate limits are waited out rather than raised — a poll is precisely
 * the caller that can afford to — while any other error surfaces.
 */
async function waitForExperimentRows(
  backendClient: BackendClient,
  datasetId: string,
  experimentId: string,
  expected: number,
  timeoutMs: number = QUERYABLE_TIMEOUT_MS,
  pollMs: number = QUERYABLE_POLL_MS,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  let lastSwallowed: unknown = null;
  while (Date.now() - start < timeoutMs) {
    try {
      // size 1: the total is in the envelope, so there is no reason to transfer
      // 5,000 rows in order to count them.
      seen = (
        await backendClient.compareItemsPage({
          datasetId,
          experimentIds: [experimentId],
          size: 1,
        })
      ).total;
      if (seen === expected) return;
    } catch (err) {
      if (!isRateLimitedError(err)) throw err;
      seen = 'rate limited';
      lastSwallowed = err;
    }
    await sleep(pollMs);
  }
  throw new Error(
    `[deepPagedExperiment fixture] experiment ${experimentId} reported ${seen} rows, ` +
      `expected ${expected}, after ${Date.now() - start}ms` +
      (lastSwallowed === null
        ? ''
        : `; last refusal stood off: ${
            lastSwallowed instanceof Error ? lastSwallowed.message : String(lastSwallowed)
          }`),
  );
}

/**
 * A dataset big enough to page at the shipped default, and two experiments over
 * it: one settled, one written last so it is still un-aggregated when the test
 * reads it.
 *
 * Why two. `applyPushTopLimit` takes the branch OPIK-8274 rewrote only when
 * `hasRaw && !hasAggregated`, and `AggregatedExperimentCounts.hasAggregated()`
 * is false only while `aggregated == 0` — i.e. before the denormalization job
 * has run against that experiment even once. Seeding 5,000 dataset items and
 * 5,000 traces takes minutes, so an experiment created at the start of that is
 * long past the 1m debounce by the time the data is queryable: reading it would
 * exercise the aggregated branch and prove nothing about this release. The
 * second experiment reuses the rows the first one already proved queryable, so
 * its only writes are 5,000 experiment-item links — twenty paced requests —
 * after which the caller has the full debounce window to read.
 *
 * Seeded over REST rather than through the bridge's `evaluate`/`compare-seed`
 * routes, for the reason `exportComparison` and `experimentItemRead` both give:
 * those run a real `evaluate()` with `task_threads=1`, which here would be 5,000
 * sequential task runs inside a single HTTP call. These rows exist to be
 * counted, ordered, de-duplicated and checked for mispairing — they carry no
 * scores and no LLM output.
 *
 * Dataset-item ids are minted at distinct, strictly increasing milliseconds so
 * that id order and `idx` order agree. The endpoint orders by the stable dataset
 * item id, so this is what lets a caller assert the concatenated pages are
 * monotonic in `idx` — an interleave or an overlapping slice across a page
 * boundary that set equality alone cannot see. It does not pin the DIRECTION the
 * product orders in; see the spec.
 *
 * Teardown deletes both experiments, then the dataset, then the traces. None of
 * them cascades with the project, and `global-teardown`'s run-prefix sweep knows
 * about experiments, datasets and projects but not traces, so 5,000 traces would
 * otherwise be left behind on every run.
 */
export const test = baseTest.extend<DeepPagedExperimentFixtures>({
  deepPagedExperiment: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-deeppage-ds`;
    const settledExperimentName = `${testNamespace}-deeppage-settled`;
    const freshExperimentName = `${testNamespace}-deeppage-fresh`;
    const settledExperimentId = uuid7();
    const freshExperimentId = uuid7();

    // One millisecond apart, ending just before now: distinct timestamps make
    // the ids strictly ascending in `idx` order (the 48-bit millisecond field is
    // the most significant part of a v7, and `uuid7`'s remaining bits are
    // random, so ids minted inside one millisecond would not order at all).
    // Backdated rather than forward-dated because a future id is what
    // `UuidV7TimestampValidator` rejects; 5s into the past is inside any window.
    const idBaseMs = Date.now() - ITEM_COUNT;
    const itemIds = Array.from({ length: ITEM_COUNT }, (_, i) => uuid7(new Date(idBaseMs + i)));
    const traceIds = Array.from({ length: ITEM_COUNT }, () => uuid7());

    const seeded: DeepPagedItemSeed[] = itemIds.map((datasetItemId, i) => ({
      idx: i,
      datasetItemId,
      traceId: traceIds[i],
    }));

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'deep-paged experiment comparison at the shipped default page size',
    });

    // Tracked so teardown knows whether any trace write landed; the batches are
    // paced, so a failure part-way through still leaves rows to remove.
    let seededTraces = false;
    try {
      await writeBatches(
        'dataset items',
        chunk(
          itemIds.map((id, i) => ({
            id,
            data: { idx: i, input: `row-${String(i).padStart(4, '0')}` },
          })),
          BATCH_SIZE,
        ),
        (items) => backendClient.writeDatasetItemsBatch({ datasetId: dataset.id, items }),
      );

      const traces: TraceBatchSeed[] = traceIds.map((id, i) => ({
        id,
        name: `${testNamespace}-deeppage-trace-${String(i).padStart(4, '0')}`,
        input: { input: `row-${String(i).padStart(4, '0')}` },
        output: { output: `answer-${i}` },
      }));
      await writeBatches('traces', chunk(traces, BATCH_SIZE), async (batch) => {
        await backendClient.createTracesBatch({ projectName: project.name, traces: batch });
        seededTraces = true;
      });

      for (const [id, name] of [
        [settledExperimentId, settledExperimentName],
        [freshExperimentId, freshExperimentName],
      ] as const) {
        await writeWithBackoff(`experiment ${name}`, async () => {
          await backendClient.createExperiment({
            id,
            name,
            datasetName,
            projectName: project.name,
          });
        });
      }

      const links = (experimentId: string) =>
        chunk(
          seeded.map((row) => ({
            experimentId,
            datasetItemId: row.datasetItemId,
            traceId: row.traceId,
          })),
          BATCH_SIZE,
        );

      await writeBatches('settled experiment items', links(settledExperimentId), (batch) =>
        backendClient.createExperimentItems(batch),
      );

      // The wait belongs to the SETTLED experiment only. Doing it here confirms
      // the dataset items and the traces are queryable, which is what makes the
      // fresh experiment's links the last thing that has to land — and so what
      // lets the debounce window be spent on the read instead of on ingestion.
      await waitForExperimentRows(backendClient, dataset.id, settledExperimentId, ITEM_COUNT);

      await writeBatches('fresh experiment items', links(freshExperimentId), (batch) =>
        backendClient.createExperimentItems(batch),
      );
      // Stamped from the last WRITE, before the confirmation below, because the
      // debounce the caller is racing counts from the write and not from the
      // moment the rows became readable. Whatever the confirmation spends is
      // therefore charged against the caller's window, which is the honest
      // accounting — and the reason the budget above is small.
      const freshWrittenAtMs = Date.now();
      await waitForExperimentRows(
        backendClient,
        dataset.id,
        freshExperimentId,
        ITEM_COUNT,
        FRESH_QUERYABLE_TIMEOUT_MS,
        FRESH_QUERYABLE_POLL_MS,
      );

      const ref: DeepPagedExperimentRef = {
        projectId: project.id,
        projectName: project.name,
        datasetId: dataset.id,
        datasetName,
        settledExperimentId,
        settledExperimentName,
        freshExperimentId,
        freshExperimentName,
        freshWrittenAtMs,
        rawBranchWindowMs: RAW_BRANCH_WINDOW_MS,
        itemCount: ITEM_COUNT,
        seeded,
      };

      // Without dropping `seeded`: 5,000 id triples in every run's attachment
      // bury the handful of fields anyone reads.
      const { seeded: _seeded, ...summary } = ref;
      await testInfo.attach('opik.deepPagedExperiment', {
        body: JSON.stringify(summary, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
          try {
            await fn();
          } catch (err) {
            // Never rethrow from teardown: a cleanup failure must not replace
            // the test's own error.
            console.warn(`[deepPagedExperiment fixture] delete warning for ${what}:`, err);
          }
        };
        // The experiments before the dataset they reference.
        for (const [id, name] of [
          [freshExperimentId, freshExperimentName],
          [settledExperimentId, settledExperimentName],
        ] as const) {
          await safe(`experiment ${name}`, () => backendClient.deleteExperiment(id));
        }
        await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
        if (seededTraces) {
          for (const batch of chunk(traceIds, BATCH_SIZE)) {
            await safe(`${batch.length} traces`, () => backendClient.deleteTraces(batch));
          }
        }
      }
    }
  },
});

export { expect } from './feedback-score-reasons.fixture';
