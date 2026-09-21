import { test as baseTest } from './thread-cost-buckets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';
import { skipUnlessBackdatedIdsAccepted } from './uuid-window-guard';
import {
  PAST_7_DAYS_PRESET,
  DAYS_BACK_EARLY,
  deleteSeededTraces,
  past7DaysIntervalStart,
  utcDayOf,
  utcNoonDaysBack,
} from './cost-buckets';

/**
 * The 1970 sentinel a trace carries when its row is materialised before the
 * `start_time` that belongs on it — a span ingested ahead of its trace, or a
 * partial write. `notEquals(t.start_time, toDateTime64('1970-01-01 00:00:00.000', 9))`
 * is an EXACT comparison in the thread aggregate, so the seed has to write this
 * instant to the millisecond or it is testing an ordinary old trace instead.
 */
const EPOCH_SENTINEL = new Date(0);

/** The real turn of the sentinel thread: the only interval that may count. */
const SENTINEL_THREAD_REAL_MS = 2_000;

/**
 * How far past the real turn's end the sentinel row's own `end_time` reaches.
 *
 * Deliberately far past it. `maxIf` must discard this end; an unguarded `max`
 * would take it, and the gap between the two answers is what every assertion in
 * the spec is reading.
 */
const SENTINEL_THREAD_STRAY_END_MS = 60_000;

/** The clean thread alongside it — one trace, no sentinel row, nothing unusual. */
const CLEAN_THREAD_MS = 10_000;

/** One seeded thread: its id, its traces, and the duration it must report. */
export interface SentinelThreadRef {
  threadId: string;
  /** Trace ids in seeded order. */
  traceIds: string[];
  /** Exactly what the thread aggregate must report for `duration`, in ms. */
  durationMs: number;
}

export interface ThreadDurationSentinelRef {
  projectId: string;
  projectName: string;
  /** Two traces: one real, one carrying the 1970 sentinel `start_time`. */
  sentinel: SentinelThreadRef;
  /** One ordinary trace, so the average has something to be an average of. */
  clean: SentinelThreadRef;
  /** The UTC date both threads start on — the only bucket that may carry a duration. */
  startDay: string;
  /**
   * Today's UTC date, where both `trace_threads` rows were really minted. Empty
   * under OPIK-8335; the bucket everything here landed in before it.
   */
  mintedDay: string;
  /** What THREAD_AVERAGE_DURATION must report per day, in ms. */
  avgDurationByDay: Record<string, number>;
  /** Threads inside the seven-day window, and their mean duration in ms. */
  current: { threadCount: number; avgDurationMs: number };
  /**
   * The two wrong answers this seed exists to separate, in ms. Neither is
   * asserted — they are here so a failure message can name what the number it
   * got actually means, and so the margins between the three are visible in the
   * attached seed rather than only in a comment.
   *
   *   `unguardedMax` — `max(end_time)` over the thread instead of `maxIf`: the
   *     sentinel row's stray end becomes the thread's end and stretches its
   *     duration to the full 60s. This is the half-fix, and the revision this
   *     PR's own branch still carried until it was merged forward.
   *   `sentinelThreadDropped` — the sentinel thread's duration resolves NULL and
   *     `avg` skips it, leaving the clean thread's duration as the whole answer.
   */
  wrongAnswers: { unguardedMaxMs: number; sentinelThreadDroppedMs: number };
  /** The `interval_start` the Logs page sends for its `past7days` preset. */
  intervalStart: Date;
  /** The `time_range` preset key the spec must open the Logs page with. */
  timeRangePreset: string;
}

export interface ThreadDurationSentinelFixtures {
  threadDurationSentinel: ThreadDurationSentinelRef;
}

/** One trace of a seeded thread: when it claims to have run, and for how long. */
interface TraceSeed {
  label: string;
  /**
   * What goes in `start_time`. The sentinel trace's is the 1970 epoch, which is
   * the whole point — so this is written verbatim rather than derived from an
   * offset.
   */
  startTime: Date;
  endTime: Date;
}

/**
 * Seed one thread's traces.
 *
 * Every id is pushed onto `written` BEFORE the write that mints it: this seeds
 * several traces in sequence and the ids are backdated, so anything that
 * collected them only on success would strand the traces already created when a
 * later one throws — and nothing else sweeps a backdated trace.
 *
 * The id is minted at the trace's REAL instant, never at its `start_time`. For
 * the sentinel trace those differ by 56 years, and an id at the epoch would be
 * refused by every env that bounds ingested UUIDv7 timestamps — while also
 * misrepresenting the shape under test, which is a trace that arrived normally
 * and lost only its `start_time`.
 */
async function seedThread(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  label: string,
  mintedAt: Date,
  traces: TraceSeed[],
  durationMs: number,
  written: string[],
): Promise<SentinelThreadRef> {
  const threadId = `${namespace}-${label}-thread`;
  const traceIds: string[] = [];

  for (const seed of traces) {
    const traceId = uuid7(mintedAt);
    written.push(traceId);
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName,
      name: `${namespace}-${label}-${seed.label}`,
      source: 'sdk',
      threadId,
      input: `${namespace} ${label} ${seed.label} input`,
      output: `${namespace} ${label} ${seed.label} output`,
      startTime: seed.startTime,
      // Never omitted. A trace with no end_time contributes no end at all, and
      // the thread would answer a null duration for a reason that has nothing
      // to do with the sentinel guard under test.
      endTime: seed.endTime,
    });
    traceIds.push(traceId);
  }

  return { threadId, traceIds, durationMs };
}

/**
 * Two threads that start on the same day, one of which carries a trace whose
 * `start_time` is the 1970 epoch sentinel (OPIK-8335).
 *
 * A trace row can exist before the `start_time` that belongs on it does — a span
 * ingested ahead of its trace leaves exactly that. The thread aggregate derives
 * its duration as `max(end_time) - min(start_time)` over the thread's traces, so
 * such a row poisons both ends: its epoch start would drag the thread's start
 * back to 1970, and its `end_time` — which is real, and is whatever instant the
 * row was last touched — would drag the thread's end forward. #8373 guards both
 * with `notEquals(t.start_time, epoch)`; the `minIf` half landed first and the
 * `maxIf` half only at its head.
 *
 *                             duration      avg over both threads
 *   sentinel thread              2 s
 *   clean thread                10 s                  6 s
 *
 * The three answers this separates are 6s, 35s and 10s, and they are distinct in
 * the API AND after `formatDuration` truncates to whole seconds — which is why
 * the intervals are seconds-scale rather than the milliseconds the flow was
 * explored with. At 500 ms / 1500 ms / 2000 ms the correct answer renders "1s"
 * and so do both wrong ones, and the rendered assertion could not have failed.
 *
 *   * **6 s — correct.** `maxIf` discards the sentinel row's stray end, so the
 *     sentinel thread spans only its real turn.
 *   * **35 s — the half-fix.** `max(end_time)` unguarded takes the stray end and
 *     the sentinel thread reads 60 s, averaging to 35 s with the clean one.
 *   * **10 s — the thread dropped.** The sentinel thread's duration resolves NULL
 *     and `avg` skips it, leaving the clean thread's own duration.
 *
 * The clean thread is the control in both directions: it fixes the average so a
 * single wrong duration cannot hide inside it, and it stays 10 s under every one
 * of the three, so a failure that moved it too would be something other than the
 * guard.
 *
 * Both threads are anchored whole days back from noon UTC, reusing the day
 * `cost-buckets` already places spend on — well inside the Logs page's
 * `past7days` window and twelve hours clear of a daily bucket boundary, while
 * their `trace_threads` rows are minted by ingestion today.
 */
export const test = baseTest.extend<ThreadDurationSentinelFixtures>({
  threadDurationSentinel: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    /** Every trace id this fixture minted, recorded before its write. */
    const written: string[] = [];

    try {
      const startedAt = utcNoonDaysBack(DAYS_BACK_EARLY);

      // Probed with the anchor's real age, not `DAYS_BACK_EARLY * DAY_MS`: the
      // anchor is noon UTC, so after noon it is up to twelve hours older than
      // that, and a window sized at exactly four days would accept the probe
      // and then reject the seed — failing as an opaque 400 where it should
      // have skipped.
      await skipUnlessBackdatedIdsAccepted(
        backendClient,
        project.name,
        Date.now() - startedAt.getTime(),
      );

      const sentinelThread = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'sentinel',
        startedAt,
        [
          {
            label: 'real-turn',
            startTime: startedAt,
            endTime: new Date(startedAt.getTime() + SENTINEL_THREAD_REAL_MS),
          },
          {
            // The row with no usable start_time. Its end_time is real and
            // reaches well past the real turn's, so a `max` that does not
            // discard it changes the answer rather than coinciding with it.
            label: 'sentinel-row',
            startTime: EPOCH_SENTINEL,
            endTime: new Date(startedAt.getTime() + SENTINEL_THREAD_STRAY_END_MS),
          },
        ],
        SENTINEL_THREAD_REAL_MS,
        written,
      );

      const cleanThread = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'clean',
        startedAt,
        [
          {
            label: 'only-turn',
            startTime: startedAt,
            endTime: new Date(startedAt.getTime() + CLEAN_THREAD_MS),
          },
        ],
        CLEAN_THREAD_MS,
        written,
      );

      // One call for both, which is the endpoint's batch path. Closing is how
      // a conversation reaches its terminal state, and it is what the flow was
      // explored through — an open thread is a different row status, and
      // pinning the duration of one would leave the closed case unasserted.
      await backendClient.closeThreads({
        projectName: project.name,
        threadIds: [sentinelThread.threadId, cleanThread.threadId],
      });

      const startDay = utcDayOf(startedAt);
      const avgDurationMs = (SENTINEL_THREAD_REAL_MS + CLEAN_THREAD_MS) / 2;

      const ref: ThreadDurationSentinelRef = {
        projectId: project.id,
        projectName: project.name,
        sentinel: sentinelThread,
        clean: cleanThread,
        startDay,
        mintedDay: utcDayOf(new Date()),
        avgDurationByDay: { [startDay]: avgDurationMs },
        current: { threadCount: 2, avgDurationMs },
        wrongAnswers: {
          unguardedMaxMs: (SENTINEL_THREAD_STRAY_END_MS + CLEAN_THREAD_MS) / 2,
          sentinelThreadDroppedMs: CLEAN_THREAD_MS,
        },
        intervalStart: past7DaysIntervalStart(),
        timeRangePreset: PAST_7_DAYS_PRESET,
      };

      await testInfo.attach('opik.threadDurationSentinel', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // In a `finally` and driven by what was actually written, so a failure
      // part-way through the second thread still takes the first one's traces
      // with it: deleting the project does not take its traces, and
      // `global-teardown`'s run-prefix sweep does not know about traces at all.
      if (!shouldLeaveArtifacts(testInfo) && written.length > 0) {
        await deleteSeededTraces(backendClient, written, 'threadDurationSentinel');
      }
    }
  },
});

export { expect } from './thread-cost-buckets.fixture';
