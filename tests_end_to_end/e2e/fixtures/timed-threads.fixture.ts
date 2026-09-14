import { test as baseTest } from './summarised-datasets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

/**
 * One thread whose wall-clock span is an exact, chosen number of milliseconds.
 *
 * `durationMs` is the axis under test. The backend derives a thread's duration
 * as `dateDiff('microsecond', min(start_time), max(end_time)) / 1000` over the
 * thread's traces, so a thread that has to span an exact interval has to
 * control both ends of it — which the SDK bridge cannot do (it stamps its own
 * times), hence the REST seeds below.
 */
export interface TimedThreadRef {
  threadId: string;
  /** Trace ids in the order they were seeded, oldest first. */
  traceIds: string[];
  /** Exactly what `GET /v1/private/traces/threads` must report for `duration`. */
  durationMs: number;
  /** What `formatDuration(durationMs, false)` must render in the thread panel. */
  expectedDisplay: string;
}

export interface DurationThreadsRef {
  projectId: string;
  projectName: string;
  /**
   * An hour plus a remainder, spanned by two traces.
   *
   * 3_615_300 ms is not an arbitrary interval: `3615.3 % 3600` is
   * 15.300000000000182 in IEEE-754 doubles, so this is the value that renders
   * the raw float when the hour remainder is not re-rounded (OPIK-8248).
   */
  hourPlusRemainder: TimedThreadRef;
  /**
   * 5 ms, spanned by a single trace — the other end of the same formatter
   * branch, and the one a "round the remainder harder" fix would break by
   * flattening a real 5 ms interval to "0s".
   */
  subSecond: TimedThreadRef;
}

export interface TimedThreadsFixtures {
  durationThreads: DurationThreadsRef;
}

/** ms between the thread's first trace start and its last trace end. */
const HOUR_PLUS_REMAINDER_MS = 3_615_300;
const SUB_SECOND_MS = 5;

/** Length of the first trace of the two-turn thread; immaterial to the total. */
const FIRST_TURN_MS = 1_000;

interface TraceSeed {
  label: string;
  /** ms after the thread's first start_time. */
  startOffsetMs: number;
  endOffsetMs: number;
}

const HOUR_PLUS_REMAINDER_TRACES: TraceSeed[] = [
  { label: 'turn-1', startOffsetMs: 0, endOffsetMs: FIRST_TURN_MS },
  {
    label: 'turn-2',
    startOffsetMs: 3_600_000,
    // The thread's last end_time, and so the one that fixes its duration.
    endOffsetMs: HOUR_PLUS_REMAINDER_MS,
  },
];

const SUB_SECOND_TRACES: TraceSeed[] = [
  { label: 'turn-1', startOffsetMs: 0, endOffsetMs: SUB_SECOND_MS },
];

async function seedThread(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  label: string,
  firstStart: Date,
  traces: TraceSeed[],
  durationMs: number,
  expectedDisplay: string,
): Promise<TimedThreadRef> {
  const threadId = `${namespace}-${label}-thread`;
  const traceIds: string[] = [];

  for (const seed of traces) {
    const id = uuid7();
    await backendClient.createTraceWithSource({
      id,
      projectName,
      name: `${namespace}-${label}-${seed.label}`,
      source: 'sdk',
      threadId,
      input: `${namespace} ${label} ${seed.label} input`,
      output: `${namespace} ${label} ${seed.label} output`,
      startTime: new Date(firstStart.getTime() + seed.startOffsetMs),
      // Never omitted: a trace with no end_time contributes no end to the
      // thread's `max(end_time)`, and the aggregate answers a null duration —
      // which no rendering assertion could tell apart from a formatter bug.
      endTime: new Date(firstStart.getTime() + seed.endOffsetMs),
    });
    traceIds.push(id);
  }

  return { threadId, traceIds, durationMs, expectedDisplay };
}

/**
 * Two threads in one project, each spanning an exact interval, for the two ends
 * of `formatDuration(_, false)` — the thread panel's own header formatter.
 *
 * Seeded through `POST /v1/private/traces` rather than the SDK bridge because
 * the interval IS the subject: the bridge stamps `start_time`/`end_time` from
 * its own clock, so a thread seeded through it spans however long the seed
 * happened to take, and neither of the two intervals below could be reproduced.
 *
 * Both threads land in the same project so one page load can reach either, and
 * so the thread list the spec reads has a known total.
 *
 * Teardown deletes the traces explicitly: deleting a project does not take its
 * traces with it, and `global-teardown`'s run-prefix sweep does not know about
 * traces at all.
 */
export const test = baseTest.extend<TimedThreadsFixtures>({
  durationThreads: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Anchored so both threads END at roughly "now" — a thread whose start is
    // an hour back still sits inside every default Logs window, while one
    // seeded an hour into the future would not.
    const now = Date.now();

    const seeded: TimedThreadRef[] = [];
    try {
      const hourPlusRemainder = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'hour-remainder',
        new Date(now - HOUR_PLUS_REMAINDER_MS),
        HOUR_PLUS_REMAINDER_TRACES,
        HOUR_PLUS_REMAINDER_MS,
        '1h 15.3s',
      );
      seeded.push(hourPlusRemainder);

      const subSecond = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'sub-second',
        new Date(now - SUB_SECOND_MS),
        SUB_SECOND_TRACES,
        SUB_SECOND_MS,
        '0.005s',
      );
      seeded.push(subSecond);

      const ref: DurationThreadsRef = {
        projectId: project.id,
        projectName: project.name,
        hourPlusRemainder,
        subSecond,
      };

      await testInfo.attach('opik.durationThreads', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // In a `finally`, and driven by what was actually written: a failure
      // seeding the second thread must still take the first one's traces with
      // it, or the next run's thread list starts with a stranger in it.
      if (!shouldLeaveArtifacts(testInfo) && seeded.length > 0) {
        const ids = seeded.flatMap((t) => t.traceIds);
        try {
          await backendClient.deleteTraces(ids);
        } catch (err) {
          console.warn('[durationThreads fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './summarised-datasets.fixture';
