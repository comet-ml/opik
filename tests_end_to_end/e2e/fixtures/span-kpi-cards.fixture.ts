import { test as baseTest } from './paged-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed } from '../core/backend';

/** What the seeded project must total over one of the two KPI periods. */
export interface SpanKpiPeriodExpectation {
  count: number;
  /** Percentage in [0, 100], as the `errors` card reports it. */
  errorRate: number;
  /** Milliseconds. */
  avgDuration: number;
  /** USD. */
  totalCost: number;
}

export interface SpanKpiSpansRef {
  traceIds: string[];
  current: SpanKpiPeriodExpectation;
  previous: SpanKpiPeriodExpectation;
  /**
   * The `interval_start` the Logs page sends for its `past7days` preset: UTC
   * start of day, six days back. The backend derives the previous period by
   * shifting the current one back by its own length, so both windows follow
   * from this one value and the spec must use exactly the same one.
   */
  intervalStart: Date;
  /** The `time_range` preset key the spec must open the Logs page with. */
  timeRangePreset: string;
}

export interface SpanKpiSpansFixtures {
  spanKpiSpans: SpanKpiSpansRef;
}

/**
 * `claude-haiku-4.5-20251001` at 1M prompt + 1M completion tokens prices at
 * $1/M in + $5/M out = exactly $6.00 from the shipped price table — the same
 * vector `span-cost-resolution.spec.ts` pins, so if the table moves the two
 * specs fail together and say so.
 *
 * A million of each is also what lifts every per-span cost clear of
 * `formatCost`'s `<$0.01` floor, so the rendered card reads an exact dollar
 * amount rather than a placeholder.
 */
const COST_MODEL = 'claude-haiku-4.5-20251001';
const COST_PROVIDER = 'anthropic';
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;
const COST_PER_SPAN = 6;

/**
 * Durations in the current period: 100…800ms, averaging exactly 450.
 *
 * Deliberately not eight identical spans. An average that equals every member
 * is also what a query returning any single row would report, so a duration
 * aggregate that had collapsed to one span would still pass.
 */
const CURRENT_DURATIONS_MS = [100, 200, 300, 400, 500, 600, 700, 800];
/** Flat 200ms in the prior period, so the period-over-period delta is exactly +125%. */
const PREVIOUS_DURATIONS_MS = [200, 200, 200, 200];
/** Two of the eight current spans carry `error_info` — an error rate of exactly 25%. */
const CURRENT_ERROR_COUNT = 2;

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Ages, in days, of the spans in each period.
 *
 * Every window here is keyed on the instant the span's UUIDv7 id embeds, and
 * the current window runs from UTC midnight six days back to now — between 6
 * and 7 days long depending on the hour the run starts, with the previous
 * window the same length again before it. These ages sit at least a day inside
 * both bounds at every hour of the day, so nothing turns on when the suite
 * happens to run.
 */
const CURRENT_AGE_DAYS = [1.2, 1.7, 2.2, 2.7, 3.2, 3.7, 4.2, 4.7];
const PREVIOUS_AGE_DAYS = [8.5, 9.5, 10.5, 11.5];

const ERROR_INFO = {
  exceptionType: 'ValueError',
  message: 'seeded span failure',
  traceback: 'seeded span failure',
};

/** UTC start of day, `days` back — how the front end builds `past7days`. */
function utcStartOfDayAgo(days: number): Date {
  const at = new Date(Date.now() - days * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

/**
 * Twelve LLM spans in one fresh project: eight in the last seven days (two of
 * them errored, durations 100–800ms) and four in the seven days before that
 * (flat 200ms, clean).
 *
 * The smallest seed for which all four KPI cards *and* all four
 * period-over-period deltas come out as distinct, exact numbers:
 *
 *   count          8 / 4      -> +100%
 *   error rate    25% / 0%    -> +25pp   (percentage points, not percent)
 *   avg duration 450 / 200ms  -> +125%
 *   total cost    $48 / $24   -> +100%
 *
 * Distinct matters: three cards sharing a delta would let a card wired to the
 * wrong metric pass. So would eight identical durations, which is why the
 * current period's are spread.
 *
 * Both periods are placed by minting each span's id at the age it needs, not by
 * its `start_time`: the KPI query windows on the id. `start_time`/`end_time`
 * still carry the age as well, because they are what the duration is measured
 * from.
 */
export const test = baseTest.extend<SpanKpiSpansFixtures>({
  spanKpiSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    const seedPeriod = async (
      label: string,
      ages: number[],
      durations: number[],
      errorCount: number,
    ): Promise<void> => {
      if (ages.length !== durations.length) {
        throw new Error(
          `[spanKpiSpans fixture] ${label}: ${ages.length} ages but ${durations.length} durations`,
        );
      }

      // One trace per period, aged with its spans so it sits in the same window.
      const traceMoment = new Date(Date.now() - ages[0] * DAY_MS);
      const traceId = uuid7(traceMoment);
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-${label}`,
        source: 'sdk',
        input: `${testNamespace} ${label}`,
        output: `${testNamespace} ${label}`,
        startTime: traceMoment,
        endTime: new Date(traceMoment.getTime() + 1_000),
      });
      traceIds.push(traceId);

      const spans: SpanBatchSeed[] = ages.map((ageDays, i) => {
        const startTime = new Date(Date.now() - ageDays * DAY_MS);
        return {
          id: uuid7(startTime),
          traceId,
          name: `${testNamespace}-${label}-s${i}`,
          type: 'llm' as const,
          startTime,
          endTime: new Date(startTime.getTime() + durations[i]),
          model: COST_MODEL,
          provider: COST_PROVIDER,
          // No `total_cost`: the server prices the span, which is what makes
          // the Total cost card's number the backend's answer rather than ours.
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
          },
          ...(i < errorCount ? { errorInfo: ERROR_INFO } : {}),
        };
      });

      await backendClient.createSpansBatch({ projectName: project.name, spans });
    };

    const mean = (values: number[]) => values.reduce((a, b) => a + b, 0) / values.length;

    try {
      await seedPeriod('current', CURRENT_AGE_DAYS, CURRENT_DURATIONS_MS, CURRENT_ERROR_COUNT);
      await seedPeriod('previous', PREVIOUS_AGE_DAYS, PREVIOUS_DURATIONS_MS, 0);

      const ref: SpanKpiSpansRef = {
        traceIds: [...traceIds],
        current: {
          count: CURRENT_DURATIONS_MS.length,
          errorRate: (CURRENT_ERROR_COUNT / CURRENT_DURATIONS_MS.length) * 100,
          avgDuration: mean(CURRENT_DURATIONS_MS),
          totalCost: CURRENT_DURATIONS_MS.length * COST_PER_SPAN,
        },
        previous: {
          count: PREVIOUS_DURATIONS_MS.length,
          errorRate: 0,
          avgDuration: mean(PREVIOUS_DURATIONS_MS),
          totalCost: PREVIOUS_DURATIONS_MS.length * COST_PER_SPAN,
        },
        intervalStart: utcStartOfDayAgo(6),
        timeRangePreset: 'past7days',
      };

      await testInfo.attach('opik.spanKpiSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        try {
          // Explicit: a project delete does not take its traces, and the
          // run-prefix sweep in global-teardown does not know about them.
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[spanKpiSpans fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './paged-spans.fixture';
