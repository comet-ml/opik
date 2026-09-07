import { test, expect } from '@e2e/fixtures';
import { PROJECT_METRIC_TYPES, type MetricSeries } from '@e2e/core/backend';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * The per-project metrics read behind a dashboard Time series widget scoped to
 * a single project — `POST /v1/private/projects/{id}/metrics` (OPIK-7725).
 *
 * A different endpoint from the one `workspace-span-metrics.spec.ts` covers,
 * and deliberately so: that spec ticks "All projects in the workspace", which
 * routes the widget to `/workspaces/metrics/spans` and never reaches this code
 * at all. Nothing in the estate called this endpoint before.
 *
 * What can go wrong here is a hard error, not a wrong number. The project
 * predicate is rendered per metric type — the seven span-time metrics declare
 * `IN :project_ids`, the other thirteen the scalar `:project_id` — and R2DBC
 * raises `NoSuchElementException` for a bind the rendered SQL does not declare.
 * A metric on the wrong side of that partition is a 500 on a chart people read
 * daily. Which is why the first test asks for every member of the enum rather
 * than a representative sample: a partition can only be checked exhaustively.
 *
 * The remaining tests then pin numbers, because a 200 is not the whole
 * contract. `SPAN_COUNT` coming back as exactly the seeded 7 in a workspace of
 * thousands of projects is what proves the project predicate rendered at all: a
 * dropped predicate would not error, it would answer with the whole workspace.
 *
 * SCOPE — the same two layers as the workspace spec. The API tests pin the
 * bucketing, the scoping and the breakdown arithmetic, none of which can be
 * read back off a chart. The last test drives the real widget config dialog, so
 * the capability tag names something a browser actually exercised.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** The UTC day a DAILY bucket falls on, as the fixture records its seeds. */
const bucketDate = (point: { time: string }): string => point.time.slice(0, 10);

/**
 * Sum of one named series across the window.
 *
 * The series must be present: an absent `total_tokens` means the aggregation
 * returned nothing, which is a failure rather than a zero. Individual points
 * may be null — `WITH FILL` pads empty buckets — and those genuinely are zero.
 */
const seriesTotal = (series: MetricSeries[], name: string): number => {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  return found!.points.reduce((acc, p) => acc + (p.value ?? 0), 0);
};

test.describe('Project span metrics — data contract', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  /**
   * The fixture seeds four backdated traces and blocks until every span is
   * queryable, which the default budget cannot contain. Declared on the
   * describe so it covers fixture setup too.
   */
  test.slow();

  test(
    'every metric type is served for a single project rather than erroring on its project bind',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ projectMetricSpans, project, backendClient }) => {
      const window = { intervalStart: projectMetricSpans.windowStart, intervalEnd: new Date() };

      const failures: string[] = [];
      await test.step('All 20 metric types answer 200', async () => {
        for (const metricType of PROJECT_METRIC_TYPES) {
          const { status, message } = await backendClient.projectMetric({
            projectId: project.id,
            metricType,
            interval: 'DAILY',
            ...window,
          });
          if (status !== 200) failures.push(`${metricType} -> ${status}: ${message}`);
        }
        // Reported together rather than one-at-a-time: the value of this test is
        // knowing *which* half of the partition broke, and failing on the first
        // metric would hide the rest.
        expect(failures, 'metric types the endpoint refused').toEqual([]);
        // Guards the loop itself — an empty vocabulary would pass silently.
        expect(PROJECT_METRIC_TYPES.length, 'every MetricType was driven').toBe(20);
      });

      await test.step('So does every span breakdown the widget offers', async () => {
        // Only the span metrics take model/provider/type; NAME and TAGS are
        // shared with trace metrics. A breakdown on a token or duration metric
        // needs a sub_metric — validation answers 422 for a blank one, so an
        // omitted one would report a bad payload as a product defect.
        const breakdowns: Array<{ metricType: 'SPAN_COUNT' | 'SPAN_TOKEN_USAGE' | 'SPAN_DURATION'; field: string; subMetric?: string }> = [];
        for (const field of ['model', 'provider', 'type', 'name']) {
          breakdowns.push({ metricType: 'SPAN_COUNT', field });
          breakdowns.push({ metricType: 'SPAN_TOKEN_USAGE', field, subMetric: 'total_tokens' });
          breakdowns.push({ metricType: 'SPAN_DURATION', field, subMetric: 'p50' });
        }

        const breakdownFailures: string[] = [];
        for (const breakdown of breakdowns) {
          const { status, message } = await backendClient.projectMetric({
            projectId: project.id,
            metricType: breakdown.metricType,
            interval: 'DAILY',
            ...window,
            breakdown: { field: breakdown.field, subMetric: breakdown.subMetric },
          });
          if (status !== 200) {
            breakdownFailures.push(`${breakdown.metricType} by ${breakdown.field} -> ${status}: ${message}`);
          }
        }
        expect(breakdownFailures, 'breakdowns the endpoint refused').toEqual([]);
        expect(breakdowns.length, 'every span breakdown variant was driven').toBe(12);
      });
    },
  );

  test(
    'the read is scoped to the one project and bucketed on the day each span was stamped',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ projectMetricSpans, project, backendClient }) => {
      const { days, totals } = projectMetricSpans;
      const window = { intervalStart: projectMetricSpans.windowStart, intervalEnd: new Date() };

      await test.step('SPAN_COUNT is exactly the seeded span count', async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_COUNT',
          interval: 'DAILY',
          ...window,
        });
        expect(status, `SPAN_COUNT rejected with: ${message}`).toBe(200);
        // The project is fresh and the workspace holds thousands of others, so
        // this number is the project predicate. Had it been dropped, the answer
        // would be enormous rather than an error.
        expect(seriesTotal(series, 'spans'), 'the spans series over the window').toBe(totals.spanCount);
      });

      await test.step('Each DAILY bucket carries the tokens seeded on that day, and no other bucket carries any', async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_TOKEN_USAGE',
          interval: 'DAILY',
          ...window,
        });
        expect(status, `SPAN_TOKEN_USAGE rejected with: ${message}`).toBe(200);

        const totalTokens = series.find((s) => s.name === 'total_tokens');
        expect(totalTokens, 'the answer carries a "total_tokens" series').toBeDefined();

        const byDate = new Map<string, number>();
        for (const point of totalTokens!.points) {
          byDate.set(bucketDate(point), (byDate.get(bucketDate(point)) ?? 0) + (point.value ?? 0));
        }

        for (const day of days) {
          expect(byDate.get(day.bucketDate), `total_tokens on ${day.bucketDate} (day -${day.ageDays})`)
            .toBe(day.totalTokens);
        }

        // The other half of the assertion, and the half a per-day comparison
        // alone would miss: nothing leaked into a day that was never seeded. A
        // query that ignored its bucket expression would put the whole 274 in
        // one bucket and still satisfy the loop above for that one day.
        const seededDates = new Set(days.map((d) => d.bucketDate));
        const strays = [...byDate.entries()].filter(([date, value]) => !seededDates.has(date) && value !== 0);
        expect(strays, 'buckets outside the seeded days are empty').toEqual([]);
      });

      await test.step('Every usage series matches the seed', async () => {
        const { series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_TOKEN_USAGE',
          interval: 'DAILY',
          ...window,
        });
        expect(seriesTotal(series, 'total_tokens'), 'total_tokens').toBe(totals.totalTokens);
        expect(seriesTotal(series, 'prompt_tokens'), 'prompt_tokens').toBe(totals.promptTokens);
        expect(seriesTotal(series, 'completion_tokens'), 'completion_tokens').toBe(totals.completionTokens);
      });

      await test.step('A window that closes before the seed aggregates to nothing', async () => {
        // Without this, every assertion above would also hold for an endpoint
        // that ignored interval_start/interval_end and read all of time.
        const { status, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_COUNT',
          interval: 'DAILY',
          intervalStart: projectMetricSpans.windowStart,
          intervalEnd: new Date(Date.now() - 5 * DAY_MS),
        });
        expect(status, 'a window ending before the oldest seeded day').toBe(200);
        const spans = series.find((s) => s.name === 'spans');
        // Absent and zero are the same answer for a window that matched
        // nothing, unlike the seeded windows above where an absent series
        // would mean the aggregation returned nothing at all.
        expect(spans?.points.reduce((acc, p) => acc + (p.value ?? 0), 0) ?? 0,
          'spans before the seed').toBe(0);
      });
    },
  );

  test(
    'a provider breakdown splits the seeded tokens by provider instead of repeating the total',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ projectMetricSpans, project, backendClient }) => {
      const { totals, totalTokensByProvider } = projectMetricSpans;

      await test.step('Each provider series carries its own share', async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_TOKEN_USAGE',
          interval: 'DAILY',
          intervalStart: projectMetricSpans.windowStart,
          intervalEnd: new Date(),
          breakdown: { field: 'provider', subMetric: 'total_tokens' },
        });
        expect(status, `provider breakdown rejected with: ${message}`).toBe(200);

        const names = series.map((s) => s.name).sort();
        // The whole answer, not just the rows we were hoping for: an extra
        // series would mean the breakdown reached beyond this project.
        expect(names, 'one series per seeded provider, and nothing else')
          .toEqual(Object.keys(totalTokensByProvider).sort());

        for (const [provider, expected] of Object.entries(totalTokensByProvider)) {
          expect(seriesTotal(series, provider), `total_tokens for ${provider}`).toBe(expected);
        }

        // The seed splits unevenly, so a breakdown that ignored its group
        // expression would report the grand total under each provider — which
        // the per-provider assertions above would catch only because these two
        // numbers differ. State that they do.
        for (const value of Object.values(totalTokensByProvider)) {
          expect(value, 'a provider share is smaller than the grand total').toBeLessThan(totals.totalTokens);
        }
        expect(
          Object.values(totalTokensByProvider).reduce((a, b) => a + b, 0),
          'the per-provider totals account for the whole seed',
        ).toBe(totals.totalTokens);
      });
    },
  );

  test(
    'a Time series widget scoped to one project reads the per-project endpoint and renders its chart',
    {
      tag: [
        '@cap:dashboards.create-dashboard',
        '@cap:dashboards.add-widget',
        '@cap:dashboards.configure-widget',
      ],
    },
    async ({ projectMetricSpans, project, backendClient, registerDashboardCleanup, page }) => {
      const dashboards = new DashboardsPage(page);
      // `handleMetricTypeChange` seeds `usageMetrics: ['total_tokens']` when the
      // metric becomes Span token usage, and the widget title is generated from
      // that pair.
      const widgetTitle = 'Span token usage - total_tokens';

      await test.step('The seeded spans are queryable before the widget is built', async () => {
        // Otherwise a slow ingest renders an empty chart and this fails as a UI
        // defect, which it would not be.
        await expect
          .poll(
            async () => {
              const { status, series } = await backendClient.projectMetric({
                projectId: project.id,
                metricType: 'SPAN_TOKEN_USAGE',
                interval: 'DAILY',
                intervalStart: projectMetricSpans.windowStart,
                intervalEnd: new Date(),
              });
              if (status !== 200) return -1;
              const found = series.find((s) => s.name === 'total_tokens');
              return found ? found.points.reduce((acc, p) => acc + (p.value ?? 0), 0) : 0;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(projectMetricSpans.totals.totalTokens);
      });

      await test.step('Open Dashboards and create one', async () => {
        await dashboards.goto();
        await dashboards.waitForReady();
        // Registered the moment the id exists: a dashboard belongs to the
        // workspace, so neither the project fixture nor the run-prefix sweep
        // would ever remove it.
        registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
      });

      const metricsRead = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
          new URL(r.url()).pathname === `/api/v1/private/projects/${project.id}/metrics`,
        { timeout: 60_000 },
      );

      await test.step('Configure the widget: this project, Span token usage', async () => {
        await dashboards.addProjectSpanTokenUsageWidget(project.name);
      });

      await test.step('The widget reads the per-project metrics endpoint, not the workspace one', async () => {
        // The endpoint is the thing the widget's project scope decides, and it
        // is invisible in the rendered chart — a workspace-scoped widget draws
        // an equally plausible line from a different query. Assert the status
        // too: a mis-gated project bind surfaces here as a 500 while the chart
        // simply stays blank.
        const response = await metricsRead;
        expect(response.status(), 'the per-project metrics read').toBe(200);
      });

      await test.step('The widget renders the total_tokens series', async () => {
        // The chart plots points as SVG geometry, so the rendered pixels cannot
        // be compared to a token count — that is what the data-contract tests
        // above are for. What the UI must prove is that the widget resolved its
        // query and drew the answer rather than an empty or errored state.
        expect(
          await dashboards.widgetSeriesNames(widgetTitle),
          'the chart legend names the selected usage series',
        ).toContain('total_tokens');
      });
    },
  );
});
