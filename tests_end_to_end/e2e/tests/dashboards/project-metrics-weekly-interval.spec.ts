import { test, expect } from '@e2e/fixtures';
import { PROJECT_METRIC_TYPES, type MetricSeries } from '@e2e/core/backend';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * Project metrics at the WEEKLY interval — the branch `calculateIntervalType`
 * takes for any range longer than 30 days (OPIK-7791).
 *
 * Every dashboards spec in the estate stops at `past30days`, which is served
 * HOURLY or DAILY. WEEKLY is exercised by nothing, and it is not merely "the
 * same query with a bigger step": the interval decides the `WITH FILL` bucket
 * expression and its `FROM`/`TO` bounds, and where those three disagree on type
 * ClickHouse raises and the endpoint answers 500 — on a chart people read
 * daily, rendered to the user as an empty widget.
 *
 * Scope, deliberately: `past60days` only. The `alltime` preset currently sends
 * no `interval_start` at all (`calculateIntervalConfig` returns undefined for
 * it and `useProjectMetric` drops the key), so the request is refused with a
 * 400 before any WEEKLY query runs. A case for it here would be asserting a
 * front-end request-contract bug rather than this interval branch, so it is
 * left out and noted instead.
 *
 * Two layers, matching the neighbouring dashboards specs. The API tests pin the
 * status across the whole metric-type enum and the bucketing to exact numbers,
 * neither of which is readable off a chart. The UI test then drives the real
 * date-range control on a real widget, so the capability tag names something a
 * browser exercised.
 */

const DAY_MS = 24 * 60 * 60 * 1000;
const WEEK_MS = 7 * DAY_MS;

/**
 * Sum of one named series across the window.
 *
 * The series must be present: an absent one means the aggregation returned
 * nothing, which is a failure rather than a zero. Individual points may be null
 * — `WITH FILL` pads empty buckets — and those genuinely are zero.
 */
const seriesTotal = (series: MetricSeries[], name: string): number => {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  return found!.points.reduce((acc, p) => acc + (p.value ?? 0), 0);
};

test.describe(
  'Project metrics at the WEEKLY interval — data contract',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    /**
     * The fixture backdates eight traces across 52 days and blocks until their
     * spans are queryable, which the default budget cannot contain. Declared on
     * the describe so it covers fixture setup too.
     */
    test.slow();

    test(
      'every metric type is served at WEEKLY over a range longer than 30 days',
      { tag: ['@cap:dashboards.metric-date-range'] },
      async ({ weeklyMetricSpans, project, backendClient }) => {
        const window = { intervalStart: weeklyMetricSpans.windowStart, intervalEnd: new Date() };

        await test.step('All 20 metric types answer 200', async () => {
          const failures: string[] = [];
          for (const metricType of PROJECT_METRIC_TYPES) {
            const { status, message } = await backendClient.projectMetric({
              projectId: project.id,
              metricType,
              interval: 'WEEKLY',
              ...window,
            });
            if (status !== 200) failures.push(`${metricType} -> ${status}: ${message}`);
          }
          // Reported together rather than one at a time: the value of this test
          // is knowing *which* metrics broke, and failing on the first would
          // hide the rest.
          expect(failures, 'metric types the endpoint refused at WEEKLY').toEqual([]);
          // Guards the loop itself — an empty vocabulary would pass silently.
          expect(PROJECT_METRIC_TYPES.length, 'every MetricType was driven').toBe(20);
        });
      },
    );

    test(
      'the WEEKLY answer buckets seven days at a time and carries the whole seed',
      { tag: ['@cap:dashboards.metric-date-range'] },
      async ({ weeklyMetricSpans, project, backendClient }) => {
        const { totals } = weeklyMetricSpans;
        const window = { intervalStart: weeklyMetricSpans.windowStart, intervalEnd: new Date() };

        const readSpanCount = () =>
          backendClient.projectMetric({
            projectId: project.id,
            metricType: 'SPAN_COUNT',
            interval: 'WEEKLY',
            ...window,
          });

        await test.step('The seeded spans are queryable', async () => {
          await expect
            .poll(
              async () => {
                const { status, series } = await readSpanCount();
                if (status !== 200) return -1;
                const found = series.find((s) => s.name === 'spans');
                return found ? found.points.reduce((acc, p) => acc + (p.value ?? 0), 0) : 0;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(totals.spanCount);
        });

        await test.step('Consecutive buckets are exactly one week apart', async () => {
          const { status, message, series } = await readSpanCount();
          expect(status, `SPAN_COUNT at WEEKLY rejected with: ${message}`).toBe(200);

          const spans = series.find((s) => s.name === 'spans');
          expect(spans, 'the answer carries a "spans" series').toBeDefined();
          const times = spans!.points.map((p) => new Date(p.time).getTime());
          // More than one bucket, or "weekly" would be indistinguishable from
          // the TOTAL interval, which also answers 200 over this window.
          expect(times.length, 'buckets across a 59-day window').toBeGreaterThan(1);

          const gaps = times.slice(1).map((t, i) => t - times[i]);
          // The whole discrimination in one line: a DAILY answer over the same
          // window returns the same total spread over gaps of one day.
          expect(new Set(gaps), 'every gap between consecutive buckets').toEqual(
            new Set([WEEK_MS]),
          );
        });

        await test.step('Token usage over the window matches the seed', async () => {
          const { status, message, series } = await backendClient.projectMetric({
            projectId: project.id,
            metricType: 'SPAN_TOKEN_USAGE',
            interval: 'WEEKLY',
            ...window,
          });
          expect(status, `SPAN_TOKEN_USAGE at WEEKLY rejected with: ${message}`).toBe(200);
          // The project is fresh and the workspace holds thousands of others, so
          // this number is the project predicate: had it been dropped, the
          // answer would be enormous rather than an error.
          expect(seriesTotal(series, 'total_tokens'), 'total_tokens over the window').toBe(
            totals.totalTokens,
          );
        });

        await test.step('A window that closes before the seed aggregates to nothing', async () => {
          // Without this, everything above would also hold for an endpoint that
          // ignored interval_start/interval_end and read all of time.
          //
          // A window entirely before the oldest seeded week (52 days back), and
          // still longer than 30 days so it is one a WEEKLY range could really
          // be. It cannot reuse `windowStart` as its start: the request is
          // validated for start-before-end and would be refused with a 400
          // rather than answering an empty aggregate.
          const { status, series } = await backendClient.projectMetric({
            projectId: project.id,
            metricType: 'SPAN_COUNT',
            interval: 'WEEKLY',
            intervalStart: new Date(Date.now() - 120 * DAY_MS),
            intervalEnd: new Date(Date.now() - 60 * DAY_MS),
          });
          expect(status, 'a window ending before the oldest seeded week').toBe(200);
          const spans = series.find((s) => s.name === 'spans');
          // Absent and zero are the same answer for a window that matched
          // nothing, unlike the seeded window above where an absent series
          // would mean the aggregation returned nothing at all.
          expect(
            spans?.points.reduce((acc, p) => acc + (p.value ?? 0), 0) ?? 0,
            'spans before the seed',
          ).toBe(0);
        });
      },
    );

    test(
      'a widget set to Past 60 days reads the metrics endpoint at WEEKLY and still renders its chart',
      {
        tag: [
          '@cap:dashboards.metric-date-range',
          '@cap:dashboards.create-dashboard',
          '@cap:dashboards.add-widget',
          // The widget is scoped to one project and given its metric through
          // the widget dialog before the range is touched at all — the same
          // assertions project-span-metrics files under this key. Untagged it
          // would be coverage the map cannot see.
          '@cap:dashboards.configure-widget',
        ],
      },
      async ({
        weeklyMetricSpans,
        project,
        backendClient,
        registerDashboardCleanup,
        page,
      }) => {
        const dashboards = new DashboardsPage(page);
        // `handleMetricTypeChange` seeds `usageMetrics: ['total_tokens']` when
        // the metric becomes Span token usage, and the widget title is
        // generated from that pair.
        const widgetTitle = 'Span token usage - total_tokens';

        await test.step('The seeded spans are queryable before the widget is built', async () => {
          // Otherwise a slow ingest renders an empty chart and this fails as a
          // UI defect, which it would not be.
          await expect
            .poll(
              async () => {
                const { status, series } = await backendClient.projectMetric({
                  projectId: project.id,
                  metricType: 'SPAN_TOKEN_USAGE',
                  interval: 'WEEKLY',
                  intervalStart: weeklyMetricSpans.windowStart,
                  intervalEnd: new Date(),
                });
                if (status !== 200) return -1;
                const found = series.find((s) => s.name === 'total_tokens');
                return found ? found.points.reduce((acc, p) => acc + (p.value ?? 0), 0) : 0;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(weeklyMetricSpans.totals.totalTokens);
        });

        await test.step('Open Dashboards and create one', async () => {
          await dashboards.goto();
          await dashboards.waitForReady();
          // Registered the moment the id exists: a dashboard belongs to the
          // workspace, so neither the project fixture nor the run-prefix sweep
          // would ever remove it.
          registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
        });

        await test.step('Add a Time series widget scoped to the seeded project', async () => {
          await dashboards.addProjectSpanTokenUsageWidget(project.name);

          // Settle on the rendered chart before going near the date-range
          // control. The widget dialog has only just closed, and Radix keeps a
          // pointer-blocking layer mounted for a beat afterwards — a click on
          // the range combobox inside that beat is swallowed, the popover never
          // opens, and the failure reads as a missing preset. Waiting for the
          // chart is a real settle point rather than a sleep, and it also
          // states the widget worked at the default range before the range is
          // changed, so a lost chart afterwards is attributable.
          expect(
            await dashboards.widgetSeriesNames(widgetTitle),
            'the chart renders at the default range before the range is changed',
          ).toContain('total_tokens');
          expect(await dashboards.selectedDateRange(), 'the default preset').toBe('Past 30 days');
        });

        const isProjectMetrics = (url: string) =>
          new URL(url).pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
          new URL(url).pathname === `/api/v1/private/projects/${project.id}/metrics`;

        const weeklyRead = page.waitForResponse(
          (r) => isProjectMetrics(r.url()) && (r.request().postData() ?? '').includes('WEEKLY'),
          { timeout: 60_000 },
        );

        await test.step('Switch the date range to Past 60 days', async () => {
          await dashboards.selectDateRange(weeklyMetricSpans.dateRangeLabel);
          expect(await dashboards.selectedDateRange(), 'the selected preset').toBe(
            weeklyMetricSpans.dateRangeLabel,
          );
        });

        await test.step('The widget re-reads at WEEKLY, with an interval_start, and is served', async () => {
          const response = await weeklyRead;
          const payload = JSON.parse(response.request().postData() ?? '{}') as {
            interval?: string;
            interval_start?: string;
          };
          expect(payload.interval, 'the interval the widget asked for').toBe('WEEKLY');
          // The `alltime` preset omits this key and is refused with a 400
          // before the query runs; a preset that reached WEEKLY must not.
          expect(
            payload.interval_start,
            'the widget sent an interval_start',
          ).toEqual(expect.any(String));
          // A mis-typed WITH FILL bound surfaces here as a 500 while the chart
          // simply stays blank.
          expect(response.status(), 'the WEEKLY metrics read').toBe(200);
        });

        await test.step('The widget renders the series rather than the empty state', async () => {
          // The chart plots points as SVG geometry, so the rendered pixels
          // cannot be compared to a token count — that is what the data-contract
          // tests above are for. What the UI must prove is that the widget
          // resolved its WEEKLY query and drew the answer.
          expect(
            await dashboards.widgetSeriesNames(widgetTitle),
            'the chart legend names the selected usage series',
          ).toContain('total_tokens');
        });
      },
    );
  },
);
