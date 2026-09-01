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
 * 1970-01-01. Its ISO week starts Monday 1969-12-29, which is below the floor
 * of ClickHouse's 16-bit `Date` (1970-01-01) — so a week bound taken on the
 * stored id underflows and wraps to ~2149-06-06 unless the expression widens to
 * `Date32` first. That wrap is what OPIK-7456 removed from the read path, and
 * it is the only half of the fix reachable while `id_at` is still a `DateTime`.
 */
const EPOCH_WEEK_MOMENT = new Date(0);

/** Above the 16-bit `Date` ceiling (2149-06-06) at the other end. */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2201, 0, 15));

/** How long a just-written trace may take to become readable. */
const READABLE_TIMEOUT_MS = 30_000;
const READABLE_POLL_MS = 500;

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
      if ((await backendClient.getTracePayload(id)) !== null) pending.delete(id);
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
  try {
    // Explicit: deleting the project does not take its traces with it, and
    // global-teardown's run-prefix sweep does not know about traces at all.
    await backendClient.deleteTraces(traces.map((t) => t.id));
  } catch (err) {
    console.warn('[idAgedTraces fixture] trace delete warning:', err);
  }
}

export const test = baseTest.extend<IdAgedTracesFixtures>({
  epochWindowTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const epoch = await seedAgedTrace(
      backendClient,
      project.name,
      testNamespace,
      'epoch',
      EPOCH_WEEK_MOMENT,
    );
    const present = await seedAgedTrace(
      backendClient,
      project.name,
      testNamespace,
      'present',
      new Date(),
    );
    const seeded = [epoch, present];
    await assertSeedIsReady(backendClient, seeded);

    await testInfo.attach('opik.epochWindowTraces', {
      body: JSON.stringify(seeded, null, 2),
      contentType: 'application/json',
    });

    await use({ epoch, present });

    if (!shouldLeaveArtifacts(testInfo)) await deleteSeeded(backendClient, seeded);
  },

  idAgedTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const epoch = await seedAgedTrace(
      backendClient,
      project.name,
      testNamespace,
      'epoch',
      EPOCH_WEEK_MOMENT,
    );
    const present = await seedAgedTrace(
      backendClient,
      project.name,
      testNamespace,
      'present',
      new Date(),
    );
    const farFuture = await seedAgedTrace(
      backendClient,
      project.name,
      testNamespace,
      'far-future',
      FAR_FUTURE_MOMENT,
    );
    const seeded = [epoch, present, farFuture];
    await assertSeedIsReady(backendClient, seeded);

    await testInfo.attach('opik.idAgedTraces', {
      body: JSON.stringify(seeded, null, 2),
      contentType: 'application/json',
    });

    await use({ epoch, present, farFuture });

    if (!shouldLeaveArtifacts(testInfo)) await deleteSeeded(backendClient, seeded);
  },
});

export { expect } from './dashboard-cleanup.fixture';
