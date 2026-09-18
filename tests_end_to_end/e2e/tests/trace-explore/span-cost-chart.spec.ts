import { test, expect, bucketTotal, bucketsByDay, expectBucketsByDay } from '@e2e/fixtures';
import { toMetricSeries } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The chart under the Spans tab's Total cost card: which series it plots, and
 * which day each bar sits on (OPIK-8335).
 *
 * `span-kpi-cards.spec.ts` asserts the four cards above this chart and their
 * deltas, and stops there. Nothing in the estate reads the series below them,
 * which is exactly where this failure lived: the chart asked the metrics
 * endpoint for trace-level `COST` for every entity type, so on the Spans tab it
 * drew a series that summed to the card above it — to the penny — while putting
 * the money on the wrong days. There is no rendered symptom of that. A reader
 * sees a healthy chart whose total agrees with its card and has no way to know
 * the shape is another entity's.
 *
 * So the seed forces the two apart (see `span-cost-buckets.fixture.ts`):
 *
 *              early day   late day   total
 *   SPAN_COST        $6        $18      $24
 *   COST            $18         $6      $24
 *
 * Mirror images of one identical total, which makes "the chart sums to the
 * card" and "the chart is the right series" two facts that fail separately.
 *
 * Both surfaces. The API half pins the arithmetic and proves the seed really
 * discriminates before a browser is opened — a UI assertion over a seed that
 * silently failed to set up is a test that cannot fail. The UI half is the
 * layer the PR's own backend integration tests cannot see: which `metric_type`
 * the page asks for, which is where the defect actually was.
 */

/** The `interval` the Logs page derives from its `past7days` preset. */
const DAILY = 'DAILY';

/**
 * What the Total cost card renders for the seeded $24.
 *
 * Spelled out rather than recomputed: re-implementing `formatCost` here would
 * make the assertion agree with itself whatever that function did. Four spans
 * priced at $6.00 each is $24, and `formatCost` floors to two decimals with a
 * `$` prefix — "$24".
 */
const EXPECTED_TOTAL_COST_CARD = '$24';

test.describe('Spans cost chart — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /** The fixture backdates four spans and the reads poll for ingestion. */
  test.slow();

  test(
    'span cost and trace cost bucket the same spend on opposite days',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ spanCostBuckets, project, backendClient }) => {
      const window = {
        intervalStart: spanCostBuckets.intervalStart,
        intervalEnd: new Date(),
      };

      const readSpanCost = () =>
        backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_COST',
          interval: DAILY,
          ...window,
        });

      await test.step('The seeded spans are priced and queryable', async () => {
        // Ingestion is eventually consistent and the server prices each span
        // after it lands, so poll the total rather than sleep.
        await expect
          .poll(
            async () => {
              const { status, series } = await readSpanCost();
              if (status !== 200) return -1;
              return bucketTotal(bucketsByDay(series, 'span_cost'));
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBeCloseTo(spanCostBuckets.totalCostUsd, 2);
      });

      await test.step('SPAN_COST buckets each span on its own day', async () => {
        const { status, message, series } = await readSpanCost();
        expect(status, `SPAN_COST rejected with: ${message}`).toBe(200);
        expectBucketsByDay(
          bucketsByDay(series, 'span_cost'),
          spanCostBuckets.spanCostByDay,
          'SPAN_COST',
        );
      });

      await test.step('Trace COST buckets the same money on the other day', async () => {
        // The negative control, and the reason every assertion below is worth
        // making: without it, a chart that had never stopped asking for COST
        // would satisfy "the series totals $24" just as well. It also proves
        // the seed itself discriminates — two metrics that happened to agree
        // here would make the UI test unfalsifiable.
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'COST',
          interval: DAILY,
          ...window,
        });
        expect(status, `COST rejected with: ${message}`).toBe(200);

        const traceCost = bucketsByDay(series, 'cost');
        expectBucketsByDay(traceCost, spanCostBuckets.traceCostByDay, 'COST');
        expect(
          bucketTotal(traceCost),
          'the two metrics total the same spend, which is what makes their shapes the only discriminator',
        ).toBeCloseTo(spanCostBuckets.totalCostUsd, 2);
      });
    },
  );

  test(
    'the Spans Total cost chart asks for SPAN_COST and draws it on the span days',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ spanCostBuckets, project, backendClient, page }) => {
      const isProjectMetrics = (url: string) => {
        const { pathname } = new URL(url);
        return (
          pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
          pathname === `/api/v1/private/projects/${project.id}/metrics`
        );
      };

      const metricTypeOf = (postData: string | null): string | undefined =>
        (JSON.parse(postData ?? '{}') as { metric_type?: string }).metric_type;

      await test.step('The seeded spans are priced and queryable', async () => {
        // Before the browser, so a slow ingest fails here — where it reads as
        // what it is — rather than as an empty chart the page is blamed for.
        await expect
          .poll(
            async () => {
              const { status, series } = await backendClient.projectMetric({
                projectId: project.id,
                metricType: 'SPAN_COST',
                interval: DAILY,
                intervalStart: spanCostBuckets.intervalStart,
                intervalEnd: new Date(),
              });
              if (status !== 200) return -1;
              return bucketTotal(bucketsByDay(series, 'span_cost'));
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBeCloseTo(spanCostBuckets.totalCostUsd, 2);
      });

      const logs = new LogsPage(page);

      // The chart's first read, for the count card the page opens on. Armed
      // before the navigation so it cannot be missed, and awaited before the
      // click so the cost read below is not raced by a count read still in
      // flight.
      const countChartRead = page.waitForResponse(
        (r) => isProjectMetrics(r.url()) && metricTypeOf(r.request().postData()) === 'SPAN_COUNT',
        { timeout: 90_000 },
      );

      await test.step('Open Logs in its Spans view over the seeded window', async () => {
        await logs.gotoSpans(project.id, { timeRange: spanCostBuckets.timeRangePreset });
        await expect(
          logs.metricsCardValue('total_cost'),
          'the Total cost card resolves to the seeded spend',
        ).toHaveText(EXPECTED_TOTAL_COST_CARD, { timeout: 60_000 });
        await countChartRead;
      });

      // Anything but the metric the page loaded with. Deliberately not "the
      // request for SPAN_COST": that would be the assertion doing the waiting,
      // and a build still asking for COST would time out here with "no
      // response" instead of failing on the metric type it actually sent. The
      // chart also refetches every 30s, so an unfiltered wait could catch a
      // count refetch and assert against the wrong request.
      const costChartRead = page.waitForResponse(
        (r) => isProjectMetrics(r.url()) && metricTypeOf(r.request().postData()) !== 'SPAN_COUNT',
        { timeout: 60_000 },
      );

      await test.step('Select the Total cost card', async () => {
        await logs.selectMetricsCard('total_cost');
      });

      const response = await costChartRead;

      await test.step('The chart asks for span cost, not trace cost', async () => {
        const payload = JSON.parse(response.request().postData() ?? '{}') as {
          metric_type?: string;
          interval?: string;
          interval_start?: string;
        };
        expect(
          payload.metric_type,
          'the Spans tab must read SPAN_COST — "COST" here is the regression: trace-level spend under a Spans card',
        ).toBe('SPAN_COST');
        expect(payload.interval, 'the interval past7days implies').toBe(DAILY);
        // Without one the request is refused before any bucketing runs, and
        // the buckets asserted below would be a different query's.
        expect(payload.interval_start, 'the chart sent an interval_start').toEqual(
          expect.any(String),
        );
        expect(response.status(), 'the SPAN_COST read').toBe(200);
      });

      await test.step('The answer it drew is span_cost, on the span days', async () => {
        // Read off the page's own traffic rather than asked for again: what is
        // under test is the series the chart was handed, and a second request
        // of our own could differ in window or interval and still agree.
        const series = toMetricSeries(await response.json());
        expect(
          series.map((s) => s.name).sort(),
          'one series, named for span cost — "cost" would be the trace-level series',
        ).toEqual(['span_cost']);

        const byDay = bucketsByDay(series, 'span_cost');
        expectBucketsByDay(byDay, spanCostBuckets.spanCostByDay, 'the rendered chart');
        expect(
          bucketTotal(byDay),
          'the chart sums to the Total cost card above it',
        ).toBeCloseTo(spanCostBuckets.totalCostUsd, 2);
      });
    },
  );
});
