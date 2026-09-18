import { test as baseTest } from './export-comparison.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed } from '../core/backend';
import { skipUnlessBackdatedIdsAccepted } from './uuid-window-guard';
import {
  COST_PER_SPAN_USD,
  DAYS_BACK_EARLY,
  DAYS_BACK_LATE,
  costedSpan,
  deleteSeededTraces,
  utcDayOf,
  utcNoonDaysBack,
} from './cost-buckets';

export interface SpanCostBucketsRef {
  traceIds: string[];
  spanIds: string[];
  /** The two UTC dates the seed places spend on, `YYYY-MM-DD`. */
  earlyDay: string;
  lateDay: string;
  /** What SPAN_COST must report per day, in USD. */
  spanCostByDay: Record<string, number>;
  /**
   * What trace-level COST reports per day over the same window — the mirror
   * image of `spanCostByDay`, and the answer a chart that never stopped asking
   * for COST would draw.
   */
  traceCostByDay: Record<string, number>;
  /** USD across the window; identical under either metric. */
  totalCostUsd: number;
  spanCount: number;
  /**
   * The `interval_start` the Logs page sends for its `past7days` preset: UTC
   * start of day, six days back.
   */
  intervalStart: Date;
  /** The `time_range` preset key the spec must open the Logs page with. */
  timeRangePreset: string;
}

export interface SpanCostBucketsFixtures {
  spanCostBuckets: SpanCostBucketsRef;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** UTC start of day, `days` back — how the front end builds `past7days`. */
function utcStartOfDayAgo(days: number): Date {
  const at = new Date(Date.now() - days * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

/**
 * Four priced spans in a fresh project, arranged so that spend bucketed by SPAN
 * time and spend bucketed by TRACE time are exact mirror images of each other
 * (OPIK-8335).
 *
 *            early day        late day      total
 *   SPAN_COST      $6             $18         $24
 *   COST          $18              $6         $24
 *
 * Both metrics total the same $24, so "the chart sums to the card above it" and
 * "the chart is the RIGHT series" are separate, independently-failing facts.
 * That separation is the whole seed: before OPIK-8335 the chart under the Spans
 * tab's Total cost card asked for trace-level COST, which agreed with the card
 * to the penny while drawing the money on the wrong days — a wrongness nothing
 * about the rendered page could betray.
 *
 * The shape is ordinary, not contrived: one trace opened on the early day and
 * still running on the late day, carrying a span at each end, plus a second
 * trace that both starts and finishes on the late day.
 *
 *   trace "spanning"  id @ early   spans: $6 @ early, $12 @ late
 *   trace "late"      id @ late    spans: $6 @ late
 *
 * Every instant is minted rather than observed — `span_time` and `trace_time`
 * are both `UUIDv7ToDateTime(id)`, so which bucket a row lands in is fixed by
 * the seed and not by when the suite happens to run. See `cost-buckets.ts` for
 * why the anchors are whole days back from noon UTC.
 */
export const test = baseTest.extend<SpanCostBucketsFixtures>({
  spanCostBuckets: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const early = utcNoonDaysBack(DAYS_BACK_EARLY);
      const late = utcNoonDaysBack(DAYS_BACK_LATE);

      // Everything here is backdated by days, so a reject-mode env refuses the
      // very first write. Probed with the age of the oldest instant actually
      // seeded, not with `DAYS_BACK_EARLY * DAY_MS`: the anchors are noon UTC,
      // so after noon they are up to twelve hours older than that, and a window
      // sized at exactly four days would accept the probe and then reject the
      // seed — failing as an opaque 400 where it should have skipped.
      await skipUnlessBackdatedIdsAccepted(
        backendClient,
        project.name,
        Date.now() - early.getTime(),
      );

      const spanningTraceId = uuid7(early);
      // Registered before the write, not after: a create that commits and then
      // fails the client's own check would otherwise leave a backdated trace
      // behind with nothing holding its id. `deleteTraces` is a no-op for an id
      // that never landed, so the over-registration costs nothing.
      traceIds.push(spanningTraceId);
      await backendClient.createTraceWithSource({
        id: spanningTraceId,
        projectName: project.name,
        name: `${testNamespace}-spanning`,
        source: 'sdk',
        input: `${testNamespace} spanning input`,
        output: `${testNamespace} spanning output`,
        startTime: early,
        // Runs to the late day, which is what makes a span there coherent
        // rather than a span that started after its own trace finished.
        endTime: new Date(late.getTime() + 1_000),
      });

      const lateTraceId = uuid7(late);
      traceIds.push(lateTraceId);
      await backendClient.createTraceWithSource({
        id: lateTraceId,
        projectName: project.name,
        name: `${testNamespace}-late`,
        source: 'sdk',
        input: `${testNamespace} late input`,
        output: `${testNamespace} late output`,
        startTime: late,
        endTime: new Date(late.getTime() + 1_000),
      });

      const spans: SpanBatchSeed[] = [
        costedSpan({ traceId: spanningTraceId, name: `${testNamespace}-spanning-early`, moment: early }),
        costedSpan({ traceId: spanningTraceId, name: `${testNamespace}-spanning-late-a`, moment: late }),
        costedSpan({ traceId: spanningTraceId, name: `${testNamespace}-spanning-late-b`, moment: late }),
        costedSpan({ traceId: lateTraceId, name: `${testNamespace}-late-span`, moment: late }),
      ];
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      const earlyDay = utcDayOf(early);
      const lateDay = utcDayOf(late);

      const ref: SpanCostBucketsRef = {
        traceIds: [...traceIds],
        spanIds: spans.map((s) => s.id),
        earlyDay,
        lateDay,
        // One span on the early day, three on the late one.
        spanCostByDay: {
          [earlyDay]: COST_PER_SPAN_USD,
          [lateDay]: 3 * COST_PER_SPAN_USD,
        },
        // The spanning trace's three spans all attributed to its own early id,
        // the late trace's one span to its late id.
        traceCostByDay: {
          [earlyDay]: 3 * COST_PER_SPAN_USD,
          [lateDay]: COST_PER_SPAN_USD,
        },
        totalCostUsd: spans.length * COST_PER_SPAN_USD,
        spanCount: spans.length,
        intervalStart: utcStartOfDayAgo(6),
        timeRangePreset: 'past7days',
      };

      await testInfo.attach('opik.spanCostBuckets', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        // The spans go with their traces.
        await deleteSeededTraces(backendClient, traceIds, 'spanCostBuckets');
      }
    }
  },
});

export { expect } from './export-comparison.fixture';
