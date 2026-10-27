import { test as baseTest } from './deep-paged-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/** One seeded trace, with the `source` the fixture asked the API to store. */
export interface TraceSourceRef {
  id: string;
  name: string;
  /**
   * What the write declared. `null` means the request carried no `source` key at
   * all — which is a different thing from the string the read then reports for
   * it, and the reason this field exists separately from `storedSource` below.
   */
  declaredSource: 'sdk' | 'playground' | null;
  /**
   * What `GET /v1/private/traces/{id}` reports for it once written, read back by
   * the fixture rather than assumed. For the no-source trace this is what
   * `TraceDAO` bound in place of the absent value, which is the whole subject of
   * opik#8514 — a spec must not hard-code a guess at it.
   */
  storedSource: string | null;
  input: Record<string, string>;
  output: Record<string, string>;
}

export interface TraceSourcesRef {
  projectId: string;
  projectName: string;
  /** `source: sdk` — the trace a partial update must not downgrade. */
  sdk: TraceSourceRef;
  /**
   * Written with no `source` key at all: the shape of every row that predates
   * source tracking, and the one the `source = sdk` filter's legacy fallback
   * exists to keep visible.
   */
  legacy: TraceSourceRef;
  /**
   * `source: playground`. Never the subject of an assertion about the update —
   * it is here so that "the filter returned my traces" cannot also be satisfied
   * by a filter that returned the whole project.
   */
  decoy: TraceSourceRef;
  /** All three, in seed order. */
  all: TraceSourceRef[];
}

export interface TraceSourceFixtures {
  traceSources: TraceSourcesRef;
}

/** How long a just-written trace may take to become readable. */
const READABLE_TIMEOUT_MS = 30_000;
const READABLE_POLL_MS = 500;

async function seedTrace(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  label: string,
  declaredSource: 'sdk' | 'playground' | null,
): Promise<TraceSourceRef> {
  const ref: TraceSourceRef = {
    id: uuid7(),
    name: `${namespace}-${label}`,
    declaredSource,
    storedSource: null,
    input: { question: `${namespace} question ${label}` },
    output: { answer: `${namespace} answer ${label}` },
  };

  await backendClient.createTraceWithSource({
    id: ref.id,
    projectName,
    name: ref.name,
    source: declaredSource,
    input: ref.input,
    output: ref.output,
  });

  return ref;
}

/**
 * Block until every seeded trace is readable, and record the `source` the API
 * reports for each.
 *
 * Both halves are load-bearing. The REST write answers 201 before the row is
 * queryable, so an immediate read legitimately 404s. And the spec's subject is
 * what an update does to a stored source, so a seed that never stored the source
 * it declared has to fail HERE — asserting "the source survived the PATCH"
 * against a trace that was written as `unknown` from the start is a test that
 * cannot fail, and it would read as coverage forever.
 */
async function resolveStoredSources(
  backendClient: BackendClient,
  traces: TraceSourceRef[],
): Promise<void> {
  const start = Date.now();
  const pending = new Map(traces.map((t) => [t.id, t]));
  while (pending.size > 0 && Date.now() - start < READABLE_TIMEOUT_MS) {
    for (const [id, trace] of pending) {
      const payload = await backendClient.getTracePayload(id);
      // Require the row to identify itself: a 200 without an id is not the seed
      // being readable, and treating it as ready would let the spec read a
      // `source` off a row that may not be there yet.
      if (payload !== null && payload.id === id) {
        trace.storedSource = payload.source;
        pending.delete(id);
      }
    }
    if (pending.size > 0) await new Promise((r) => setTimeout(r, READABLE_POLL_MS));
  }

  if (pending.size > 0) {
    throw new Error(
      `[traceSources fixture] traces still unreadable after ${Date.now() - start}ms: ` +
        `${[...pending.values()].map((t) => t.name).join(', ')}`,
    );
  }

  for (const trace of traces) {
    if (trace.declaredSource !== null && trace.storedSource !== trace.declaredSource) {
      throw new Error(
        `[traceSources fixture] ${trace.name} was written with source=` +
          `${trace.declaredSource} but reads back ${JSON.stringify(trace.storedSource)} — ` +
          'the seed never held the state the spec is about.',
      );
    }
  }
}

async function deleteSeeded(
  backendClient: BackendClient,
  traces: TraceSourceRef[],
): Promise<void> {
  // Explicit: deleting the project does not take its traces with it, and
  // global-teardown's run-prefix sweep does not know about traces at all.
  try {
    await backendClient.deleteTraces(traces.map((t) => t.id));
    return;
  } catch (err) {
    console.warn('[traceSources fixture] batch trace delete failed, retrying per id:', err);
  }

  // The batch is one request, so one bad id loses every other trace with it.
  // Never throws: a cleanup failure must not replace the test's own error.
  for (const trace of traces) {
    try {
      await backendClient.deleteTraces([trace.id]);
    } catch (err) {
      console.warn(`[traceSources fixture] could not delete ${trace.name}:`, err);
    }
  }
}

/**
 * Three traces in one fresh project, differing only in the `source` their write
 * declared: `sdk`, none at all, and `playground`.
 *
 * All three in ONE project on purpose. The `source` filter is scoped by project,
 * so the decoy can only prove the filter discriminates if it is inside the same
 * scope as the traces that must survive it — in a project of its own it would be
 * excluded by the `project_id` param and prove nothing.
 *
 * Seeded over REST rather than through the SDK bridge: the bridge always emits
 * `source=sdk`, and the absence of the key is half of what is under test.
 */
export const test = baseTest.extend<TraceSourceFixtures>({
  traceSources: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const seeded: TraceSourceRef[] = [];
    try {
      // Registered as each write lands, and cleaned up in the `finally`, so a
      // rejection from a later seed or a readiness timeout still deletes the
      // rows already written instead of orphaning them.
      for (const [label, source] of [
        ['sdk', 'sdk'],
        ['legacy', null],
        ['decoy', 'playground'],
      ] as const) {
        seeded.push(await seedTrace(backendClient, project.name, testNamespace, label, source));
      }
      await resolveStoredSources(backendClient, seeded);

      const [sdk, legacy, decoy] = seeded;
      const ref: TraceSourcesRef = {
        projectId: project.id,
        projectName: project.name,
        sdk,
        legacy,
        decoy,
        all: seeded,
      };

      await testInfo.attach('opik.traceSources', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && seeded.length > 0) {
        await deleteSeeded(backendClient, seeded);
      }
    }
  },
});

export { expect } from './deep-paged-experiment.fixture';
