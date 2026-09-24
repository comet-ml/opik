import { test as baseTest } from './bulk-tag-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient, type TraceBatchSeed } from '../core/backend';
import { isUuidWindowRejection, UUID_VALIDATION_SKIP_REASON } from './uuid-window-guard';

/** One seeded thread and the instant it is meant to sit at. */
export interface BoundaryThreadRef {
  threadId: string;
  traceId: string;
  /** The instant the thread's single trace carries, in its id AND its start_time. */
  moment: Date;
}

export interface BoundaryThreadsRef {
  projectId: string;
  projectName: string;
  /** Oldest first, evenly spaced. Index order IS time order. */
  threads: BoundaryThreadRef[];
  /**
   * An instant strictly between `threads[k-1]` and `threads[k]`, so no seeded
   * row sits ON it. Callers use this to partition without having to know
   * whether the endpoint's bounds are inclusive.
   */
  boundaryBefore: (k: number) => Date;
}

/**
 * 48 threads across 21 hours.
 *
 * Both numbers are bounded by what a cloud environment will accept, not chosen
 * for roundness. `UuidV7TimestampValidator` refuses an id outside a PT24H
 * window when a deployment runs it in reject mode — staging does — so 21 hours
 * back is about as wide as a seed can legally be, and every instant below sits
 * inside it. 48 rows then give ~26 minutes between neighbours, which is a wide
 * enough gap that a midpoint boundary is unambiguous.
 */
const THREAD_COUNT = 48;
const SPAN_MS = 21 * 60 * 60 * 1000;
/** How far back the newest thread sits, so nothing is seeded at "now". */
const NEWEST_OFFSET_MS = 60 * 60 * 1000;
/** Each thread's single trace is one second long, so its duration is never null. */
const TRACE_DURATION_MS = 1_000;

/** How long the 48 threads may take to finish aggregating. */
const AGGREGATED_TIMEOUT_MS = 180_000;
const AGGREGATED_POLL_MS = 2_000;

/**
 * Threads spread widely enough in time that a read window can cut BETWEEN
 * them — the case `thread-id-prefilter.spec.ts` deliberately does not reach.
 *
 * That spec windows threads too, but its window is −24h/+1h over rows seeded
 * moments ago, so every seeded row is inside it and the boundary is never
 * crossed. Granule pruning therefore never happens, which is precisely what
 * DND-1735's minmax skip index on `trace_threads.created_at` changes. A
 * wrongly-pruning index only drops rows when the query's range excludes some
 * granules, so the boundary has to fall INSIDE the data for the index to be
 * under test at all.
 *
 * Each thread is one trace, and that trace carries its instant twice — in the
 * UUIDv7 id and in `start_time`. Deliberate: a windowed thread read compares
 * against the thread row's own timestamp, and seeding both coherently means
 * the fixture describes one unambiguous instant per thread rather than relying
 * on which column the read happens to use.
 *
 * Seeded in one `POST /v1/private/traces/batch`: 48 separate writes is 48 round
 * trips to a cloud backend and the quickest way to the workspace ingestion
 * rate limit, which surfaces as a half-landed seed — the worst possible input
 * to a spec whose subject is "no row went missing".
 */
export interface BoundaryThreadsFixtures {
  boundaryThreads: BoundaryThreadsRef;
}

/**
 * Block until every seeded thread has been aggregated into `trace_threads`.
 *
 * Exactly, not at-least: a spec that partitions 48 rows cannot start against a
 * seed that only half landed — it would assert a split that was never the
 * seed's and pass.
 */
async function waitForThreads(
  backendClient: BackendClient,
  projectId: string,
  expectedIds: string[],
): Promise<void> {
  const start = Date.now();
  let seen = 0;
  while (Date.now() - start < AGGREGATED_TIMEOUT_MS) {
    const { threads } = await backendClient.listThreads({ projectId, size: expectedIds.length * 2 });
    const found = new Set(threads.map((t) => t.id));
    seen = expectedIds.filter((id) => found.has(id)).length;
    if (seen === expectedIds.length) return;
    await new Promise((r) => setTimeout(r, AGGREGATED_POLL_MS));
  }
  throw new Error(
    `[boundaryThreads fixture] only ${seen}/${expectedIds.length} threads aggregated in ` +
      `project ${projectId} after ${Date.now() - start}ms`,
  );
}

export const test = baseTest.extend<BoundaryThreadsFixtures>({
  boundaryThreads: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const newest = Date.now() - NEWEST_OFFSET_MS;
    const oldest = newest - SPAN_MS;
    const step = SPAN_MS / (THREAD_COUNT - 1);

    const threads: BoundaryThreadRef[] = Array.from({ length: THREAD_COUNT }, (_, i) => {
      const moment = new Date(Math.round(oldest + i * step));
      return {
        // Zero-padded so the thread ids sort the same way the instants do,
        // which makes a failure message readable at a glance.
        threadId: `${testNamespace}-bt-${String(i).padStart(2, '0')}`,
        traceId: uuid7(moment),
        moment,
      };
    });

    const traces: TraceBatchSeed[] = threads.map((thread, i) => ({
      id: thread.traceId,
      name: `${testNamespace}-bt-trace-${String(i).padStart(2, '0')}`,
      threadId: thread.threadId,
      input: { turn: `question ${i}` },
      output: { turn: `answer ${i}` },
      startTime: thread.moment,
      endTime: new Date(thread.moment.getTime() + TRACE_DURATION_MS),
    }));

    let seeded = false;
    try {
      try {
        await backendClient.createTracesBatch({ projectName: project.name, traces });
        seeded = true;
      } catch (err) {
        // A deployment running UUID validation in reject mode refuses the
        // whole batch. That is an environment property, not a product fault,
        // and it is the same condition `idAgedTraces` skips on.
        if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
        throw err;
      }

      await waitForThreads(
        backendClient,
        project.id,
        threads.map((t) => t.threadId),
      );

      const ref: BoundaryThreadsRef = {
        projectId: project.id,
        projectName: project.name,
        threads,
        boundaryBefore: (k: number) => {
          if (k < 1 || k > THREAD_COUNT - 1) {
            throw new Error(
              `[boundaryThreads fixture] boundaryBefore(${k}) is not an interior cut of ` +
                `${THREAD_COUNT} threads — pass 1..${THREAD_COUNT - 1}`,
            );
          }
          // The midpoint of the gap, so no seeded row sits on the boundary and
          // an inclusive bound and an exclusive one give the same partition.
          return new Date(
            Math.round((threads[k - 1].moment.getTime() + threads[k].moment.getTime()) / 2),
          );
        },
      };

      await testInfo.attach('opik.boundaryThreads', {
        body: JSON.stringify(
          {
            projectId: ref.projectId,
            count: threads.length,
            oldest: threads[0].moment.toISOString(),
            newest: threads[threads.length - 1].moment.toISOString(),
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // Explicit, and driven by whether the write landed: deleting a project
      // does not take its traces with it, and `global-teardown`'s run-prefix
      // sweep does not know about traces at all.
      if (seeded && !shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces(threads.map((t) => t.traceId));
        } catch (err) {
          console.warn('[boundaryThreads fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './bulk-tag-traces.fixture';
