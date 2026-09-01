import { test as baseTest } from './dashboard-cleanup.fixture';
import { uuid7 } from '../core/backend';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface FarFutureTracesRef {
  /** The trace whose UUIDv7 id carries a timestamp centuries from now. */
  farFuture: { id: string; name: string; threadId: string; at: Date };
  /**
   * Ordinary traces stamped minutes ago, newest first — the same order the
   * descending-id reads under test return them in.
   */
  present: Array<{ id: string; name: string }>;
  /** The thread the present-day traces share. */
  presentThreadId: string;
  /** Every seeded id, newest first: the far-future row, then `present`. */
  allIdsNewestFirst: string[];
  /**
   * A window that opens a week ago. Its lower bound sits below every seeded id
   * and its upper bound is the caller's choice — which is the axis under test.
   */
  windowStart: Date;
}

export interface FarFutureTracesFixtures {
  farFutureTraces: FarFutureTracesRef;
}

/**
 * 2200-06-15, the instant the exploration reproduced against. Any date past
 * 2149 would do — `toMonday` returns a 16-bit Date that wraps beyond it — but a
 * fixed one keeps the seed identical run to run, and it is far enough past the
 * wrap that a bucket landing near "now" cannot be a rounding artefact.
 */
const FAR_FUTURE_AT = new Date('2200-06-15T12:00:00.000Z');

/** Four ordinary rows: enough that a collapsed page is unmistakably empty. */
const PRESENT_TRACE_COUNT = 4;
const MINUTE_MS = 60 * 1000;
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;
/** Ingestion budget for the seeded rows to become queryable. */
const SEED_VISIBLE_TIMEOUT_MS = 120_000;

/**
 * One project holding four ordinary traces and one whose UUIDv7 id is stamped
 * centuries in the future (OPIK-7791, PR #8096).
 *
 * The far-future id is the entire seed. Opik's time-bounded trace reads window
 * on the timestamp embedded in the id, and each pairs its id range with a
 * week-start bound on the partition key; `toMonday` used to wrap for a
 * far-future value, so the row fell out of every windowed read it belonged in.
 * A row that vanishes that way raises nothing — no error, no empty state — so
 * the only way to see it is to seed a row on the far side of the wrap and ask
 * for it back.
 *
 * Ids are minted here rather than left to the backend for two reasons: the
 * timestamp *is* the fixture (`age_days` on the SDK bridge only reaches
 * backwards), and `POST /v1/private/traces` answers 201 with no body, so a spec
 * asserting on exact ids has to know them upfront.
 *
 * The present-day rows are the discriminator. Without them, "the far-future row
 * came back" would also be satisfied by a read that ignored its window
 * entirely; with them, every assertion can name the whole expected set and a
 * leak in either direction fails.
 *
 * Teardown deletes the traces explicitly: `ProjectService.delete` removes only
 * the project row, so traces do not cascade with it, and `global-teardown`'s
 * run-prefix sweep does not know about traces either.
 */
export const test = baseTest.extend<FarFutureTracesFixtures>({
  farFutureTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const now = Date.now();
    const presentThreadId = `${testNamespace}-present-thread`;
    const farFutureThreadId = `${testNamespace}-future-thread`;

    const farFuture = {
      id: uuid7(FAR_FUTURE_AT),
      name: `${testNamespace}-far-future`,
      threadId: farFutureThreadId,
      at: FAR_FUTURE_AT,
    };
    await backendClient.createTraceWithSource({
      id: farFuture.id,
      projectName: project.name,
      name: farFuture.name,
      source: 'sdk',
      threadId: farFutureThreadId,
      input: { question: 'seeded at 2200-06-15' },
      output: { answer: 'seeded at 2200-06-15' },
      startTime: FAR_FUTURE_AT,
      endTime: new Date(FAR_FUTURE_AT.getTime() + 1_000),
    });

    // Minutes back, not "now": a row stamped against the runner's clock can
    // land marginally ahead of the backend's and then sit outside a window
    // ending at now, which reads as a wrong answer rather than a bad seed.
    // Five-minute spacing also fixes the descending-id order the reads return.
    const present: Array<{ id: string; name: string }> = [];
    for (let i = 0; i < PRESENT_TRACE_COUNT; i++) {
      const at = new Date(now - (i + 1) * 5 * MINUTE_MS);
      const seed = { id: uuid7(at), name: `${testNamespace}-present-${i + 1}` };
      await backendClient.createTraceWithSource({
        id: seed.id,
        projectName: project.name,
        name: seed.name,
        source: 'sdk',
        threadId: presentThreadId,
        input: { question: `present-day trace ${i + 1}` },
        output: { answer: `present-day trace ${i + 1}` },
        startTime: at,
        endTime: new Date(at.getTime() + 1_000),
      });
      present.push(seed);
    }

    const allIdsNewestFirst = [farFuture.id, ...present.map((p) => p.id)];

    // Ingestion is asynchronous, so wait for the whole set before any spec
    // reads. Polling the *unwindowed* list on purpose: it is the one read the
    // wrap never affected, so a timeout here is a slow write, not the behaviour
    // under test masquerading as one.
    const deadline = Date.now() + SEED_VISIBLE_TIMEOUT_MS;
    let visible: string[] = [];
    for (;;) {
      const ids = await backendClient.listTraceIds({ projectId: project.id });
      visible = allIdsNewestFirst.filter((id) => ids.includes(id));
      if (visible.length === allIdsNewestFirst.length) break;
      if (Date.now() > deadline) {
        throw new Error(
          `[farFutureTraces fixture] only ${visible.length}/${allIdsNewestFirst.length} seeded traces ` +
            `became queryable within ${SEED_VISIBLE_TIMEOUT_MS}ms — missing ` +
            allIdsNewestFirst.filter((id) => !visible.includes(id)).join(', '),
        );
      }
      await new Promise((r) => setTimeout(r, 1_000));
    }

    // The seed only discriminates if the far-future row really is far-future.
    // A backend that clamped the id or `start_time` on write would leave every
    // window assertion in these specs trivially true, and nothing else would
    // notice — the row would still be there, just not where it claims to be.
    const beforeNow = await backendClient.listTraceIds({
      projectId: project.id,
      toTime: new Date(now),
    });
    if (beforeNow.includes(farFuture.id)) {
      throw new Error(
        `[farFutureTraces fixture] ${farFuture.id} is inside a window ending now — ` +
          'it was not stored with a far-future timestamp, so the seed proves nothing',
      );
    }

    const ref: FarFutureTracesRef = {
      farFuture,
      present,
      presentThreadId,
      allIdsNewestFirst,
      windowStart: new Date(now - WEEK_MS),
    };

    await testInfo.attach('opik.farFutureTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(allIdsNewestFirst);
      } catch (err) {
        console.warn('[farFutureTraces fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
