import type { TestInfo } from '@playwright/test';
import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * One seeded trace whose UUIDv7 id embeds a chosen instant.
 *
 * `idMoment` is the axis under test and is NOT the row's `start_time`: every
 * time-windowed trace read windows on the instant encoded in the id, so a trace
 * that has to sit provably inside or outside a window has to control its id.
 */
export interface IdAgedTraceRef {
  id: string;
  name: string;
  /** The instant encoded in `id` — what a windowed read compares against. */
  idMoment: Date;
  input: string;
  output: string;
  metadata: Record<string, string>;
}

export interface IdAgedTracesFixtures {
  /**
   * An epoch-week id and a present-day id, in one fresh project.
   *
   * Two rather than three deliberately: the far-future age below would also
   * render in a Logs table on its default range, but only because the legacy
   * `traces.id_at DateTime('UTC')` column truncates a ~2201 instant back into
   * range — a property of the storage, not of the read expression. A spec that
   * asserted on it would be asserting a schema artifact that the
   * `traces_local_v2` cutover changes.
   */
  epochWindowTraces: { epoch: IdAgedTraceRef; present: IdAgedTraceRef };

  /**
   * The same project holding all three id ages — epoch week, now, and ~2201 —
   * for assertions that are about resolving a row BY id and so are indifferent
   * to how a window would have treated it.
   */
  idAgedTraces: {
    epoch: IdAgedTraceRef;
    present: IdAgedTraceRef;
    farFuture: IdAgedTraceRef;
  };
}

/**
 * 1970-01-01. Its ISO week starts Monday 1969-12-29, below the floor of
 * ClickHouse's 16-bit `Date` — the age OPIK-7456's read-path bound has to
 * handle, and the one no other spec in the estate exercises.
 *
 * Not a regression probe for the wrap, though. `toMonday` saturates rather than
 * underflowing (ClickHouse 26.3: `toMonday(toDateTime('1970-01-01'))` is
 * `1970-01-01`), and `traces.id_at` is a 32-bit `DateTime` that truncates an
 * out-of-range instant before the week expression sees it — so on this schema
 * the pre- and post-fix expressions agree at every age seeded here. These ages
 * start discriminating once `traces_local_v2` widens `id_at` to `DateTime64`.
 */
const EPOCH_WEEK_MOMENT = new Date(0);

/**
 * Above the 16-bit `Date` ceiling (2149-06-06) at the other end. Used only by
 * assertions that resolve a row BY id, which are indifferent to windowing —
 * see the note on EPOCH_WEEK_MOMENT for why a windowed read cannot tell the
 * two expressions apart on the current schema.
 */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2201, 0, 15));

/** How long a just-written trace may take to become readable. */
const READABLE_TIMEOUT_MS = 30_000;
const READABLE_POLL_MS = 500;

/**
 * Reject-mode UUID validation refuses the ids these specs exist to seed.
 *
 * `UuidV7TimestampValidator` bounds an ingested id's embedded timestamp to
 * `[now - window, now + window]` and answers 400 when `uuidValidation.enabled=true`
 * and `auditOnly=false`. It ships disabled, so the default install seeds fine —
 * but the mode is not readable from the client, so it is detected from the
 * rejection rather than checked up front. Without this the whole spec fails as
 * an opaque 400 from a seed helper, which reads as a product bug instead of an
 * environment the spec cannot run in.
 *
 * Matched on the `message` field, not the `too_old` / `too_far_future` reason:
 * the reason lives in the response's `details`, which `rawFetch` drops when it
 * narrows the body to `message`. Verified against a reject-mode backend.
 */
function isUuidWindowRejection(err: unknown): boolean {
  const message = err instanceof Error ? err.message : String(err);
  return message.includes('Invalid UUID for id');
}

const UUID_VALIDATION_SKIP_REASON =
  'this env runs UUID timestamp validation in reject mode (UUID_VALIDATION_ENABLED=true, ' +
  'auditOnly=false), which refuses the out-of-window ids these specs seed — set auditOnly=true ' +
  'or disable validation to run them';

async function seedAgedTrace(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  label: string,
  idMoment: Date,
): Promise<IdAgedTraceRef> {
  const ref: IdAgedTraceRef = {
    id: uuid7(idMoment),
    name: `${namespace}-${label}`,
    idMoment,
    input: `${namespace} input ${label}`,
    output: `${namespace} output ${label}`,
    metadata: { seededCase: label },
  };

  await backendClient.createTraceWithSource({
    id: ref.id,
    projectName,
    name: ref.name,
    source: 'sdk',
    input: ref.input,
    output: ref.output,
    metadata: ref.metadata,
    // start_time is left at "now" for every age on purpose: the id is the only
    // axis these specs vary, and a 2201 start_time would additionally exercise
    // the write path's own range validation.
  });

  return ref;
}

/**
 * Block until every seeded trace is readable by id, and prove the ids really
 * carry the ages the specs assume.
 *
 * Both halves matter. The REST write answers 201 before the row is queryable,
 * so an immediate read legitimately 404s. And a spec whose whole subject is how
 * a read treats an out-of-range id cannot let a mis-minted id reach the
 * browser: it would assert against a fixture that silently failed to set up,
 * which reads as coverage forever.
 */
async function assertSeedIsReady(
  backendClient: BackendClient,
  traces: IdAgedTraceRef[],
): Promise<void> {
  for (const trace of traces) {
    const embedded = embeddedMillis(trace.id);
    if (embedded !== trace.idMoment.getTime()) {
      throw new Error(
        `[idAgedTraces fixture] ${trace.name}: id ${trace.id} embeds ` +
          `${new Date(embedded).toISOString()}, expected ${trace.idMoment.toISOString()}`,
      );
    }
  }

  const start = Date.now();
  const pending = new Map(traces.map((t) => [t.id, t.name]));
  while (pending.size > 0 && Date.now() - start < READABLE_TIMEOUT_MS) {
    for (const [id] of pending) {
      // Require the row to identify itself: a 200 that came back without an id
      // is not the seed being readable, and treating it as ready would let the
      // spec assert against a row that may not be there yet.
      const payload = await backendClient.getTracePayload(id);
      if (payload !== null && payload.id === id) pending.delete(id);
    }
    if (pending.size > 0) await new Promise((r) => setTimeout(r, READABLE_POLL_MS));
  }

  if (pending.size > 0) {
    throw new Error(
      `[idAgedTraces fixture] traces still unreadable after ${Date.now() - start}ms: ` +
        `${[...pending.values()].join(', ')}`,
    );
  }
}

/** The 48-bit big-endian millisecond timestamp a UUIDv7 carries, per RFC 9562. */
function embeddedMillis(id: string): number {
  return parseInt(id.replace(/-/g, '').slice(0, 12), 16);
}

async function deleteSeeded(
  backendClient: BackendClient,
  traces: IdAgedTraceRef[],
): Promise<void> {
  // Explicit: deleting the project does not take its traces with it, and
  // global-teardown's run-prefix sweep does not know about traces at all.
  try {
    await backendClient.deleteTraces(traces.map((t) => t.id));
    return;
  } catch (err) {
    console.warn('[idAgedTraces fixture] batch trace delete failed, retrying per id:', err);
  }

  // The batch is one request, so one bad id loses every other trace with it.
  // Retry individually rather than leaving rows behind, and never throw:
  // a cleanup failure must not replace the test's own error.
  for (const trace of traces) {
    try {
      await backendClient.deleteTraces([trace.id]);
    } catch (err) {
      console.warn(`[idAgedTraces fixture] could not delete ${trace.name}:`, err);
    }
  }
}

/**
 * The seed → readiness → attach → use → cleanup lifecycle both fixtures share.
 *
 * Registers each trace in `seeded` as soon as it is created, and cleans up in a
 * `finally`, so a rejection from a later seed or a readiness timeout still
 * deletes the rows already written. Registering only after the whole setup
 * succeeded would orphan them, and a leaked trace poisons the empty-state
 * assertions of whatever runs next in the project.
 */
async function withSeededTraces<T>(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  testInfo: TestInfo,
  attachmentName: string,
  labelledMoments: ReadonlyArray<readonly [label: string, idMoment: Date]>,
  shape: (refs: IdAgedTraceRef[]) => T,
  use: (value: T) => Promise<void>,
): Promise<void> {
  const seeded: IdAgedTraceRef[] = [];
  try {
    for (const [label, idMoment] of labelledMoments) {
      try {
        seeded.push(
          await seedAgedTrace(backendClient, projectName, namespace, label, idMoment),
        );
      } catch (err) {
        if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
        throw err;
      }
    }
    await assertSeedIsReady(backendClient, seeded);

    await testInfo.attach(attachmentName, {
      body: JSON.stringify(seeded, null, 2),
      contentType: 'application/json',
    });

    await use(shape(seeded));
  } finally {
    if (!shouldLeaveArtifacts(testInfo) && seeded.length > 0) {
      await deleteSeeded(backendClient, seeded);
    }
  }
}

export const test = baseTest.extend<IdAgedTracesFixtures>({
  epochWindowTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    await withSeededTraces(
      backendClient,
      project.name,
      testNamespace,
      testInfo,
      'opik.epochWindowTraces',
      [
        ['epoch', EPOCH_WEEK_MOMENT],
        ['present', new Date()],
      ],
      ([epoch, present]) => ({ epoch, present }),
      use,
    );
  },

  idAgedTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    await withSeededTraces(
      backendClient,
      project.name,
      testNamespace,
      testInfo,
      'opik.idAgedTraces',
      [
        ['epoch', EPOCH_WEEK_MOMENT],
        ['present', new Date()],
        ['far-future', FAR_FUTURE_MOMENT],
      ],
      ([epoch, present, farFuture]) => ({ epoch, present, farFuture }),
      use,
    );
  },
});

export { expect } from './dashboard-cleanup.fixture';
