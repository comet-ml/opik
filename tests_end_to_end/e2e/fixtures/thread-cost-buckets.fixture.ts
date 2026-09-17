import { test as baseTest } from './span-cost-buckets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient, type SpanBatchSeed } from '../core/backend';
import { skipUnlessBackdatedIdsAccepted } from './uuid-window-guard';
import {
  COST_PER_SPAN_USD,
  DAYS_BACK_EARLY,
  DAYS_BACK_LATE,
  DAYS_BACK_PREVIOUS,
  costedSpan,
  utcDayOf,
  utcNoonDaysBack,
} from './cost-buckets';

/** One seeded thread: its id, the traces under it, and what it must cost. */
export interface ThreadCostSeedRef {
  threadId: string;
  traceIds: string[];
  /** USD across every span under the thread. */
  costUsd: number;
  /** The UTC date of the thread's EARLIEST trace — the bucket it must land in. */
  startDay: string;
}

export interface ThreadCostBucketsRef {
  projectId: string;
  projectName: string;
  /** Two traces a day apart; its whole cost must collapse into the early bucket. */
  spanning: ThreadCostSeedRef;
  /** One trace on the late day. */
  late: ThreadCostSeedRef;
  /** One trace in the period BEFORE the seven-day window, so the cards have a delta. */
  previous: ThreadCostSeedRef;
  earlyDay: string;
  lateDay: string;
  /**
   * Today's UTC date — where every `trace_threads` row was really minted, and so
   * the bucket the pre-OPIK-8335 query put all of this spend in.
   */
  mintedDay: string;
  /** What THREAD_COST must report per day over the seven-day window, in USD. */
  threadCostByDay: Record<string, number>;
  /** What THREAD_COUNT must report per day over the same window. */
  threadCountByDay: Record<string, number>;
  /**
   * What trace-level COST reports per day over the same window — the mirror
   * image of `threadCostByDay`, and the answer a chart that never stopped
   * asking for COST would draw.
   */
  traceCostByDay: Record<string, number>;
  /** Threads and USD inside the seven-day window. */
  current: { threadCount: number; totalCostUsd: number };
  /** Threads and USD in the equal-length period immediately before it. */
  previousPeriod: { threadCount: number; totalCostUsd: number };
  /**
   * The `interval_start` the Logs page sends for its `past7days` preset: UTC
   * start of day, six days back. The backend derives the previous period by
   * shifting the current one back by its own length, so both windows follow
   * from this one value and the spec must send exactly the same one.
   */
  intervalStart: Date;
  /** The `time_range` preset key the spec must open the Logs page with. */
  timeRangePreset: string;
}

export interface ThreadCostBucketsFixtures {
  threadCostBuckets: ThreadCostBucketsRef;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** UTC start of day, `days` back — how the front end builds `past7days`. */
function utcStartOfDayAgo(days: number): Date {
  const at = new Date(Date.now() - days * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

/** One turn of a seeded conversation: when it ran and how many spans it billed. */
interface TurnSeed {
  label: string;
  moment: Date;
  spanCount: number;
}

/**
 * `written` is the fixture's own cleanup list, and every id goes into it before
 * the write that mints it. This function seeds several traces and then one span
 * batch, so anything that returned the ids only on success would strand the
 * traces already created when a later turn — or the batch — throws: they are
 * backdated, so nothing else sweeps them.
 */
async function seedThread(
  backendClient: BackendClient,
  projectName: string,
  namespace: string,
  label: string,
  turns: TurnSeed[],
  written: string[],
): Promise<ThreadCostSeedRef> {
  const threadId = `${namespace}-${label}-thread`;
  const traceIds: string[] = [];
  const spans: SpanBatchSeed[] = [];

  for (const turn of turns) {
    // Both the id and start_time carry the turn's instant. The id is what the
    // trace-level reads window and bucket on; `start_time` is what the thread
    // aggregate takes its `min()` over, and so what a thread bucket is keyed on
    // after OPIK-8335. A seed that moved only one of them could not tell a
    // corrected query from a broken one.
    const traceId = uuid7(turn.moment);
    written.push(traceId);
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName,
      name: `${namespace}-${label}-${turn.label}`,
      source: 'sdk',
      threadId,
      input: `${namespace} ${label} ${turn.label} input`,
      output: `${namespace} ${label} ${turn.label} output`,
      startTime: turn.moment,
      // Never omitted: a trace with no end_time contributes no end to the
      // thread's `max(end_time)` and the aggregate answers a null duration,
      // which would take the thread out of the cards this spec reads.
      endTime: new Date(turn.moment.getTime() + 1_000),
    });
    traceIds.push(traceId);

    for (let i = 0; i < turn.spanCount; i += 1) {
      spans.push(
        costedSpan({
          traceId,
          name: `${namespace}-${label}-${turn.label}-s${i}`,
          moment: turn.moment,
        }),
      );
    }
  }

  await backendClient.createSpansBatch({ projectName, spans });

  return {
    threadId,
    traceIds,
    costUsd: spans.length * COST_PER_SPAN_USD,
    // `min(start_time)` over the thread's traces, which the turns are ordered by.
    startDay: utcDayOf(turns[0].moment),
  };
}

/**
 * Three threads in a fresh project whose traces all ran days ago while their
 * `trace_threads` rows are minted, by ingestion, today (OPIK-8335).
 *
 * That gap is the bug and the seed reproduces it exactly: a thread's row is
 * created lazily, the first time the traces carrying its `thread_id` are
 * ingested, so the instant its UUIDv7 `thread_model_id` embeds says when the
 * data arrived and nothing at all about when the conversation happened. Reading
 * thread stats off that id put every backdated thread in today's bucket and in
 * today's KPI period.
 *
 *                     early day    late day    today     total
 *   THREAD_COST           $18          $6         -        $24
 *   THREAD_COUNT            1           1         -          2
 *   COST                   $6         $18         -        $24
 *
 * Three facts, each of which fails on its own:
 *
 *   * **the thread that spans two days.** Its turns run on the early and the
 *     late day and it bills $6 then $12, yet the whole $18 must collapse into
 *     the early bucket — a thread is bucketed once, at its start. This is the
 *     one assertion trace-level cost cannot satisfy however it is scaled.
 *   * **today is empty.** Every `trace_threads` row here was minted today, so
 *     today's bucket is where all of this landed before the fix.
 *   * **the previous-period thread.** Ten days back, so the KPI cards must
 *     report it as the prior period's only thread. Bucketed on the row's id it
 *     would have been counted in the current period instead, taking the cards'
 *     delta with it.
 *
 * The trace-level COST row above is the negative control, and it is deliberately
 * the mirror image: same $24, opposite days. A chart still asking for COST would
 * agree with the Total cost card to the penny and still be wrong.
 */
export const test = baseTest.extend<ThreadCostBucketsFixtures>({
  threadCostBuckets: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const seeded: ThreadCostSeedRef[] = [];
    /** Every trace id this fixture minted, recorded before its write. */
    const written: string[] = [];

    try {
      const earlyMoment = utcNoonDaysBack(DAYS_BACK_EARLY);
      const lateMoment = utcNoonDaysBack(DAYS_BACK_LATE);
      const previousMoment = utcNoonDaysBack(DAYS_BACK_PREVIOUS);

      // The previous-period thread is the oldest write; a reject-mode env
      // refuses it, and every window here is placed by a backdated id. Probed
      // with that instant's real age rather than `DAYS_BACK_PREVIOUS * DAY_MS`:
      // the anchors are noon UTC, so after noon they are up to twelve hours
      // older than that, and a window sized at exactly ten days would accept
      // the probe and then reject the seed — failing as an opaque 400 where it
      // should have skipped.
      await skipUnlessBackdatedIdsAccepted(
        backendClient,
        project.name,
        Date.now() - previousMoment.getTime(),
      );

      const spanningThread = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'spanning',
        [
          { label: 'turn-1', moment: earlyMoment, spanCount: 1 },
          { label: 'turn-2', moment: lateMoment, spanCount: 2 },
        ],
        written,
      );
      seeded.push(spanningThread);

      const lateThread = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'late',
        [{ label: 'turn-1', moment: lateMoment, spanCount: 1 }],
        written,
      );
      seeded.push(lateThread);

      const previousThread = await seedThread(
        backendClient,
        project.name,
        testNamespace,
        'previous',
        [{ label: 'turn-1', moment: previousMoment, spanCount: 1 }],
        written,
      );
      seeded.push(previousThread);

      const earlyDay = utcDayOf(earlyMoment);
      const lateDay = utcDayOf(lateMoment);

      const ref: ThreadCostBucketsRef = {
        projectId: project.id,
        projectName: project.name,
        spanning: spanningThread,
        late: lateThread,
        previous: previousThread,
        earlyDay,
        lateDay,
        mintedDay: utcDayOf(new Date()),
        threadCostByDay: {
          [earlyDay]: spanningThread.costUsd,
          [lateDay]: lateThread.costUsd,
        },
        threadCountByDay: {
          [earlyDay]: 1,
          [lateDay]: 1,
        },
        // Per trace id: the spanning thread's first turn on the early day, its
        // second turn and the whole late thread on the late day.
        traceCostByDay: {
          [earlyDay]: COST_PER_SPAN_USD,
          [lateDay]: 3 * COST_PER_SPAN_USD,
        },
        current: {
          threadCount: 2,
          totalCostUsd: spanningThread.costUsd + lateThread.costUsd,
        },
        previousPeriod: {
          threadCount: 1,
          totalCostUsd: previousThread.costUsd,
        },
        intervalStart: utcStartOfDayAgo(6),
        timeRangePreset: 'past7days',
      };

      await testInfo.attach('opik.threadCostBuckets', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // In a `finally` and driven by what was actually written — every id
      // recorded before its write, so a failure part-way through the third
      // thread still takes the first two and its own finished turns with it,
      // rather than leaving the next run's thread list with strangers in it.
      if (!shouldLeaveArtifacts(testInfo) && written.length > 0) {
        try {
          await backendClient.deleteTraces(written);
        } catch (err) {
          console.warn('[threadCostBuckets fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './span-cost-buckets.fixture';
