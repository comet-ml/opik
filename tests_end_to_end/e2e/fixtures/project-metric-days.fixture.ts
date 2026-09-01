import { test as baseTest } from './far-future-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** One seeded day of the window and the values every bucket for it must carry. */
export interface ProjectMetricDayRef {
  /** Whole days before "now". Never 0 — see the note on the seed below. */
  ageDays: number;
  /** `YYYY-MM-DD` (UTC) of the DAILY bucket this day's traces fall in. */
  bucketDate: string;
  /** The one feedback score seeded on this day; also the day's expected average. */
  scoreValue: number;
  /** Traces stamped on this day — 2 on the day that also carries the error. */
  traceCount: number;
  traceIds: string[];
}

export interface ProjectMetricDaysRef {
  days: ProjectMetricDayRef[];
  /** The feedback-score name, which is also the metric series name. */
  scoreName: string;
  /** Opens on a UTC day boundary two empty days before the oldest seeded day. */
  windowStart: Date;
  /**
   * A sub-window holding only the three most recent seeded days, with the
   * totals `GET /v1/private/projects/stats` must report for it.
   */
  subWindow: { fromTime: Date; toTime: Date; traceCount: number; scoreAverage: number };
  /** Every seeded trace id. */
  allTraceIds: string[];
}

export interface ProjectMetricDaysFixtures {
  projectMetricDays: ProjectMetricDaysRef;
}

/** Eleven consecutive days — enough to cross at least one Monday whenever it runs. */
const SEEDED_DAYS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
/** The day that also carries an errored trace, so its trace count is 2 not 1. */
const ERROR_DAY = 1;
const SCORE_NAME = 'bucket-value';
/** Two empty days sit between the window's start and the oldest seeded day. */
const WINDOW_DAYS = 13;
const DAY_MS = 24 * 60 * 60 * 1000;

const utcDate = (at: Date): string => at.toISOString().slice(0, 10);

/** The Monday (ISO week start) a UTC date belongs to, as `YYYY-MM-DD`. */
const weekStart = (at: Date): string => {
  const d = new Date(`${utcDate(at)}T00:00:00.000Z`);
  // getUTCDay(): 0 = Sunday. ISO weeks start Monday, which is what the read
  // path's `toDayOfWeek(..., 1)` uses.
  const offset = (d.getUTCDay() + 6) % 7;
  return utcDate(new Date(d.getTime() - offset * DAY_MS));
};

/**
 * A project carrying one scored trace per day for eleven consecutive days, plus
 * one errored trace (OPIK-7791, PR #8096).
 *
 * `ProjectMetricsDAO`'s `TRACE_FILTERED_PREFIX` — the CTE behind every
 * trace-based project metric — was rewritten by the far-future window fix, along
 * with `GET_AVERAGE_DURATION` and `GET_TOTAL_TRACE_ERRORS`. 426 lines of SQL.
 * The estate's existing guard over that endpoint asserts HTTP 200 across the 20
 * metric types, which is a binding check, not a correctness one: it would pass
 * unchanged if every bucket came back at the wrong date or with the wrong value.
 *
 * So each day gets a *distinct* score value equal to its own age. A query that
 * dropped its bucket expression and summed the window, or that shifted buckets
 * by a day, cannot land on eleven distinct values by accident — whereas eleven
 * identical days would forgive both. The one errored trace makes the daily trace
 * counts uneven too, for the same reason.
 *
 * Eleven consecutive days always contain at least one Monday, which is the
 * boundary the rewritten week bound turns on; the fixture asserts that rather
 * than trusting it.
 *
 * `ageDays` is never 0: a trace stamped against the runner's clock is a coin
 * flip against the backend's, and one landing marginally ahead of it is silently
 * excluded from any window ending at now — which reads as a wrong aggregate
 * rather than a bad seed.
 *
 * Teardown deletes the traces rather than relying on the project delete: a
 * project delete does not take its traces with it, and traces left behind would
 * be counted by a later run reading the same window.
 */
export const test = baseTest.extend<ProjectMetricDaysFixtures>({
  projectMetricDays: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const now = Date.now();
    const days: ProjectMetricDayRef[] = [];

    for (const ageDays of SEEDED_DAYS) {
      const traceIds: string[] = [];
      const scored = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-d${ageDays}`,
        input: { question: `seeded day -${ageDays}` },
        output: { answer: `seeded day -${ageDays}` },
        age_days: ageDays,
        feedback_scores: [{ name: SCORE_NAME, value: ageDays }],
        spans: [],
      });
      traceIds.push(scored.id);

      if (ageDays === ERROR_DAY) {
        const errored = await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-d${ageDays}-error`,
          input: { question: `seeded day -${ageDays} error` },
          output: { answer: `seeded day -${ageDays} error` },
          age_days: ageDays,
          error_info: {
            exception_type: 'ValueError',
            message: `seeded failure on day -${ageDays}`,
          },
          spans: [],
        });
        traceIds.push(errored.id);
      }

      days.push({
        ageDays,
        bucketDate: utcDate(new Date(now - ageDays * DAY_MS)),
        scoreValue: ageDays,
        traceCount: traceIds.length,
        traceIds,
      });
    }

    // Distinct dates are what the per-bucket assertions key on. They can only
    // collide if SEEDED_DAYS repeated, but the seed is data — say so here
    // rather than letting two days quietly collapse into one expectation that
    // then "passes".
    const dates = new Set(days.map((d) => d.bucketDate));
    if (dates.size !== days.length) {
      throw new Error(
        `[projectMetricDays fixture] seeded days share a UTC bucket: ${days.map((d) => d.bucketDate).join(', ')}`,
      );
    }

    // The week boundary is the whole reason this window is eleven days wide. If
    // it ever stopped crossing one, every assertion below would still pass while
    // no longer testing the predicate that was rewritten.
    const weeks = new Set(days.map((d) => weekStart(new Date(`${d.bucketDate}T00:00:00.000Z`))));
    if (weeks.size < 2) {
      throw new Error(
        `[projectMetricDays fixture] the seeded window does not cross a week start: ${[...weeks].join(', ')}`,
      );
    }

    // Three most recent days, bounded half a day past the oldest of them so
    // neither edge lands on a bucket boundary where inclusivity is ambiguous.
    const subDays = days.filter((d) => d.ageDays <= 3);
    const ref: ProjectMetricDaysRef = {
      days,
      scoreName: SCORE_NAME,
      windowStart: new Date(`${utcDate(new Date(now - WINDOW_DAYS * DAY_MS))}T00:00:00.000Z`),
      subWindow: {
        fromTime: new Date(now - 3.5 * DAY_MS),
        toTime: new Date(now),
        traceCount: subDays.reduce((acc, d) => acc + d.traceCount, 0),
        scoreAverage:
          subDays.reduce((acc, d) => acc + d.scoreValue, 0) / subDays.length,
      },
      allTraceIds: days.flatMap((d) => d.traceIds),
    };

    await testInfo.attach('opik.projectMetricDays', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(ref.allTraceIds);
      } catch (err) {
        console.warn('[projectMetricDays fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './far-future-traces.fixture';
