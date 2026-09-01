import { test, expect } from '@e2e/fixtures';
import type { MetricSeries } from '@e2e/core/backend';

/**
 * The values a project Time series widget plots, across a week boundary
 * (OPIK-7791, PR #8096).
 *
 * `ProjectMetricsDAO`'s `TRACE_FILTERED_PREFIX` — the CTE every trace-based
 * project metric selects through — took the far-future week-bound rewrite, along
 * with `GET_AVERAGE_DURATION` and `GET_TOTAL_TRACE_ERRORS`. The estate's
 * existing cover for that endpoint, `project-span-metrics.spec.ts`, asserts HTTP
 * 200 across the 20 metric types: a binding guard for a 426-line SQL edit, not a
 * correctness one. It would pass unchanged if every bucket came back a day late
 * or carrying the whole window's total.
 *
 * So this asserts values. The fixture gives each of eleven consecutive days a
 * distinct feedback score equal to its own age, which makes every bucket exactly
 * predictable and makes a collapsed or shifted bucketing impossible to satisfy
 * by accident. The window necessarily crosses a Monday, which is the boundary
 * the rewritten predicate turns on.
 *
 * API-level, and deliberately so. What a chart can be asked is whether it drew
 * *something* — points are SVG geometry, so a rendered series cannot be compared
 * to a seeded value, and `project-span-metrics.spec.ts` already drives the
 * project-scoped widget dialog under this same capability. The numbers are only
 * checkable here.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** The UTC day a DAILY bucket falls on, as the fixture records its seeds. */
const bucketDate = (point: { time: string }): string => point.time.slice(0, 10);

/**
 * One named series' points folded to `bucketDate -> value`.
 *
 * The series must be present: an absent one means the aggregation returned
 * nothing at all, which is a failure and not a window of zeroes. Individual
 * points may be null — `WITH FILL` pads empty buckets — and those are zero.
 */
const byDate = (series: MetricSeries[], name: string): Map<string, number> => {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  const out = new Map<string, number>();
  for (const point of found!.points) {
    out.set(bucketDate(point), (out.get(bucketDate(point)) ?? 0) + (point.value ?? 0));
  }
  return out;
};

test.describe('Project metric daily buckets — CUJ', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  // The fixture writes twelve traces and blocks on each until it is queryable.
  test.slow();

  test(
    'every daily bucket carries the value seeded on its own day, across a week boundary',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ projectMetricDays, project, backendClient }) => {
      const { days, scoreName, windowStart } = projectMetricDays;
      const window = { intervalStart: windowStart, intervalEnd: new Date() };
      const seededDates = new Set(days.map((d) => d.bucketDate));

      await test.step('Each FEEDBACK_SCORES bucket is the score seeded on that day', async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'FEEDBACK_SCORES',
          interval: 'DAILY',
          ...window,
        });
        expect(status, `FEEDBACK_SCORES rejected with: ${message}`).toBe(200);

        const values = byDate(series, scoreName);
        for (const day of days) {
          expect(
            values.get(day.bucketDate),
            `${scoreName} on ${day.bucketDate} (day -${day.ageDays})`,
          ).toBeCloseTo(day.scoreValue, 6);
        }

        // The half a per-day comparison alone would miss: nothing landed in a
        // day that was never seeded. A query that ignored its bucket expression
        // would put every score in one bucket and still satisfy the loop above
        // for that one day.
        const strays = [...values.entries()].filter(
          ([date, value]) => !seededDates.has(date) && value !== 0,
        );
        expect(strays, 'buckets outside the seeded days are empty').toEqual([]);
      });

      await test.step('Each TRACE_COUNT bucket is the number of traces stamped on that day', async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'TRACE_COUNT',
          interval: 'DAILY',
          ...window,
        });
        expect(status, `TRACE_COUNT rejected with: ${message}`).toBe(200);

        const counts = byDate(series, 'traces');
        for (const day of days) {
          expect(
            counts.get(day.bucketDate),
            `traces on ${day.bucketDate} (day -${day.ageDays})`,
          ).toBe(day.traceCount);
        }
        const strays = [...counts.entries()].filter(
          ([date, value]) => !seededDates.has(date) && value !== 0,
        );
        expect(strays, 'buckets outside the seeded days are empty').toEqual([]);

        // The project is fresh, so the window's total is exactly the seed — a
        // read that leaked another project's rows in would fail here even if
        // every seeded day happened to be right.
        const total = [...counts.values()].reduce((a, b) => a + b, 0);
        expect(total, 'the window holds exactly the seeded traces').toBe(
          days.reduce((acc, d) => acc + d.traceCount, 0),
        );
      });

      await test.step('A window closing before the seed aggregates to nothing', async () => {
        // Without this, everything above would also hold for an endpoint that
        // ignored interval_start/interval_end and read all of time.
        const { status, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'TRACE_COUNT',
          interval: 'DAILY',
          intervalStart: windowStart,
          intervalEnd: new Date(Date.now() - 12 * DAY_MS),
        });
        expect(status, 'a window ending before the oldest seeded day').toBe(200);
        const traces = series.find((s) => s.name === 'traces');
        // Absent and zero are the same answer for a window that matched
        // nothing, unlike the seeded windows above where an absent series would
        // mean the aggregation returned nothing at all.
        expect(
          traces?.points.reduce((acc, p) => acc + (p.value ?? 0), 0) ?? 0,
          'traces before the seed',
        ).toBe(0);
      });
    },
  );

  test(
    'project stats over a sub-window report the traces and score average that window holds',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ projectMetricDays, project, backendClient }) => {
      const { scoreName, subWindow } = projectMetricDays;

      await test.step('The three most recent days aggregate to their own totals', async () => {
        // A different read from the metrics endpoint above — `/projects/stats`
        // windows on the id range without bucketing — and the one the Projects
        // list renders its columns from. Same predicate underneath.
        const stats = await backendClient.getProjectStats({
          name: project.name,
          fromTime: subWindow.fromTime,
          toTime: subWindow.toTime,
        });
        const row = stats.find((s) => s.projectId === project.id);
        expect(row, `projects/stats returned a row for ${project.name}`).toBeDefined();

        expect(row!.traceCount, 'traces in the last three seeded days').toBe(subWindow.traceCount);
        // Required, not conditional: the fixture seeded a score on every one of
        // those days, so a missing average is a regression in this read and not
        // a reason to skip the comparison.
        expect(
          row!.feedbackScores[scoreName],
          `projects/stats reports a "${scoreName}" average for the sub-window`,
        ).not.toBeUndefined();
        expect(
          row!.feedbackScores[scoreName],
          `the "${scoreName}" average over the last three seeded days`,
        ).toBeCloseTo(subWindow.scoreAverage, 6);
      });
    },
  );
});
