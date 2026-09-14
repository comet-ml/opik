import { test as baseTest } from './far-future-error-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed } from '../core/backend';

/** One seeded week of the window: how far back it sits and what it carries. */
export interface WeeklyMetricDaySeed {
  ageDays: number;
  traceId: string;
  spanId: string;
  totalTokens: number;
}

export interface WeeklyMetricSpansRef {
  days: WeeklyMetricDaySeed[];
  totals: { spanCount: number; totalTokens: number };
  /**
   * The `interval_start` the dashboard sends for its `past60days` preset: UTC
   * start of day, 59 days back. Over 30 days, which is what makes
   * `calculateIntervalType` ask for WEEKLY.
   */
  windowStart: Date;
  /** The date-range preset label the widget control renders. */
  dateRangeLabel: 'Past 60 days';
}

export interface WeeklyMetricSpansFixtures {
  weeklyMetricSpans: WeeklyMetricSpansRef;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Eight LLM spans, one per week across 52 days.
 *
 * A week apart rather than a day apart on purpose: the subject here is WEEKLY
 * bucketing, and one span per bucket is what makes "consecutive buckets are
 * exactly seven days apart" a statement about the query rather than about how
 * densely the project happens to be populated. Fifty daily spans would say no
 * more and would seed six times the rows into a shared workspace that rate-limits
 * ingestion.
 *
 * The oldest sits at 52 days — inside the 59-day window, and far enough past 30
 * that no DAILY reading of the range could produce it.
 */
const WEEK_AGE_DAYS = [3, 10, 17, 24, 31, 38, 45, 52];

/**
 * Distinct per span, and deliberately not a round number each. A total that
 * agreed with any single span's usage would also be produced by an aggregation
 * that read one row, so the sum has to be reachable only by adding all eight.
 */
const TOKENS_BY_WEEK = [110, 130, 170, 190, 230, 290, 310, 370];

const MODEL = 'gpt-4o-mini';
const PROVIDER = 'openai';

/** UTC start of day, `days` back — how the front end builds `past60days`. */
function utcStartOfDayAgo(days: number): Date {
  const at = new Date(Date.now() - days * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

/**
 * A fresh project whose span history is long enough for the metrics read to be
 * served at the WEEKLY interval.
 *
 * Every existing dashboards spec stops at `past30days`, which
 * `calculateIntervalType` serves HOURLY or DAILY — so the WEEKLY branch, and
 * the `WITH FILL` bucket/`FROM`/`TO` expressions it renders, are exercised by
 * nothing. Where those three disagree on type, ClickHouse raises and the
 * endpoint answers 500 on a chart people read daily.
 *
 * Both the trace and its span are aged by minting their ids at the chosen
 * instant: the metrics query buckets on the id, not on `start_time`.
 */
export const test = baseTest.extend<WeeklyMetricSpansFixtures>({
  weeklyMetricSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const days: WeeklyMetricDaySeed[] = [];
    const spans: SpanBatchSeed[] = [];

    try {
      for (const [i, ageDays] of WEEK_AGE_DAYS.entries()) {
        const moment = new Date(Date.now() - ageDays * DAY_MS);
        const traceId = uuid7(moment);
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-w${i}`,
          source: 'sdk',
          input: `${testNamespace} input w${i}`,
          output: `${testNamespace} output w${i}`,
          startTime: moment,
          endTime: new Date(moment.getTime() + 1_000),
        });

        const totalTokens = TOKENS_BY_WEEK[i];
        const spanId = uuid7(moment);
        spans.push({
          id: spanId,
          traceId,
          name: `${testNamespace}-w${i}-span`,
          type: 'llm',
          startTime: moment,
          endTime: new Date(moment.getTime() + 500),
          model: MODEL,
          provider: PROVIDER,
          usage: {
            prompt_tokens: totalTokens - 10,
            completion_tokens: 10,
            total_tokens: totalTokens,
          },
        });

        days.push({ ageDays, traceId, spanId, totalTokens });
      }

      await backendClient.createSpansBatch({ projectName: project.name, spans });

      const ref: WeeklyMetricSpansRef = {
        days,
        totals: {
          spanCount: days.length,
          totalTokens: days.reduce((acc, d) => acc + d.totalTokens, 0),
        },
        windowStart: utcStartOfDayAgo(59),
        dateRangeLabel: 'Past 60 days',
      };

      await testInfo.attach('opik.weeklyMetricSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && days.length > 0) {
        try {
          // Explicit: deleting the project does not delete its traces, and the
          // run-prefix sweep in global-teardown does not know about them.
          await backendClient.deleteTraces(days.map((d) => d.traceId));
        } catch (err) {
          console.warn('[weeklyMetricSpans fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './far-future-error-traces.fixture';
