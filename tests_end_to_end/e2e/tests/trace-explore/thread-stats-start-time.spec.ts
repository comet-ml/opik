import { test, expect } from '@e2e/fixtures';
import { bucketTotal, bucketsByDay, expectBucketsByDay } from '@e2e/fixtures';
import type { KpiCardStat } from '@e2e/core/backend';
import { toMetricSeries } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Threads tab's metrics summary: which instant a thread's cost and count
 * are attributed to, and which period the cards put it in (OPIK-8335).
 *
 * A thread has no creation event of its own. Its `trace_threads` row is
 * materialised lazily, the first time traces carrying its `thread_id` are
 * ingested, so the instant that row's UUIDv7 id embeds records when the data
 * ARRIVED. Reading thread stats off that id therefore reported every
 * backfilled, replayed or late-delivered conversation as having happened today
 * — a chart that looks entirely healthy, with every bar in the wrong place.
 *
 * Nothing in the estate reads the Threads tab's metrics summary at all.
 * `project-metrics-weekly-interval.spec.ts` drives THREAD_COST far enough to
 * check it answers 200, and `thread-duration-format.spec.ts` reads the panel
 * header, not the cards. This is also the layer the PR's own backend
 * integration tests cannot reach: they pin the bucketing SQL across every
 * `TimeInterval`, which is deliberately not re-asserted here — what they cannot
 * see is whether the Threads tab asks for the corrected metric and renders it.
 *
 * The seed runs three conversations days ago and lets ingestion mint their
 * thread rows now (see `thread-cost-buckets.fixture.ts`):
 *
 *                     early day   late day   today   previous period
 *   THREAD_COST           $18         $6       -            $6
 *   THREAD_COUNT            1          1       -             1
 *   COST                   $6        $18       -            $6
 *
 * The trace-level COST row is the negative control and is deliberately the
 * mirror image: the same $24, on the opposite days. A chart that had never
 * stopped asking for COST would agree with the Total cost card to the penny and
 * still be wrong, so "the chart sums to the card" and "the chart is the right
 * series" are separate, independently-failing facts.
 */

/** The `interval` the Logs page derives from its `past7days` preset. */
const DAILY = 'DAILY';

/**
 * The rendered forms of the seeded current-period numbers.
 *
 * Spelled out rather than recomputed: re-implementing `formatCost` in the spec
 * would make it agree with itself whatever that function did.
 *
 *   count       2 threads in the window       -> "2"
 *   total cost  $18 + $6                      -> "$24"   (formatCost floors to 2dp)
 */
const EXPECTED_COUNT_CARD = '2';
const EXPECTED_TOTAL_COST_CARD = '$24';

/**
 * The rendered deltas against the prior period (one thread, $6).
 *
 * Distinct on purpose — a card wired to the wrong metric could not land on the
 * right delta by coincidence:
 *
 *   count       1 -> 2      +100%
 *   total cost  $6 -> $24   +300%
 */
const EXPECTED_COUNT_DELTA = '100%';
const EXPECTED_TOTAL_COST_DELTA = '300%';

/**
 * One card's two values, asserted present.
 *
 * Both are nullable in the response, and a caller that defaulted a missing one
 * to 0 would compare two absences and call it agreement — so an absence fails
 * here rather than passing silently.
 */
function kpi(stats: KpiCardStat[], type: KpiCardStat['type']): { current: number; previous: number } {
  const stat = stats.find((s) => s.type === type);
  expect(stat, `the answer carries a "${type}" card`).toBeDefined();
  expect(stat!.currentValue, `"${type}" current_value is present`).not.toBeNull();
  expect(stat!.previousValue, `"${type}" previous_value is present`).not.toBeNull();
  return { current: stat!.currentValue!, previous: stat!.previousValue! };
}

test.describe('Thread stats bucketing — CUJ', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  /**
   * The deltas are hidden below 240px per card (`getCardMode`), and the Threads
   * tab renders three cards beside the assistant sidebar. At the suite's default
   * width they do not render at all — which is by design, not a missing delta.
   */
  test.use({ viewport: { width: 2200, height: 1000 } });

  /** The fixture writes three threads across two periods and the reads poll for ingestion. */
  test.slow();

  test(
    'thread cost and thread count bucket on the thread, not on the day its row was minted',
    { tag: ['@cap:threads.thread-level-metrics'] },
    async ({ threadCostBuckets, project, backendClient }) => {
      const window = {
        intervalStart: threadCostBuckets.intervalStart,
        intervalEnd: new Date(),
      };

      const readThreadCount = () =>
        backendClient.projectMetric({
          projectId: project.id,
          metricType: 'THREAD_COUNT',
          interval: DAILY,
          ...window,
        });

      await test.step('The seeded threads have been materialised', async () => {
        // A thread row appears only once its traces are ingested, and that is
        // eventually consistent — so poll the aggregate rather than sleep.
        await expect
          .poll(
            async () => {
              const { status, series } = await readThreadCount();
              if (status !== 200) return -1;
              return bucketTotal(bucketsByDay(series, 'threads'));
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(threadCostBuckets.current.threadCount);
      });

      await test.step('Each thread is counted on the day its first trace ran', async () => {
        const { status, message, series } = await readThreadCount();
        expect(status, `THREAD_COUNT rejected with: ${message}`).toBe(200);

        const byDay = bucketsByDay(series, 'threads');
        expectBucketsByDay(byDay, threadCostBuckets.threadCountByDay, 'THREAD_COUNT');
        expect(
          byDay[threadCostBuckets.mintedDay],
          `today (${threadCostBuckets.mintedDay}) is when every trace_threads row here was really minted; ` +
            'counting the threads there is the regression',
        ).toBeUndefined();
      });

      await test.step("A thread's whole cost lands in the bucket of its earliest trace", async () => {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'THREAD_COST',
          interval: DAILY,
          ...window,
        });
        expect(status, `THREAD_COST rejected with: ${message}`).toBe(200);

        const byDay = bucketsByDay(series, 'thread_cost');
        // The spanning thread bills $6 on the early day and $12 on the late
        // one, and all $18 must show up on the early day: a thread is bucketed
        // once, at its start. No scaling of trace-level cost produces that.
        expectBucketsByDay(byDay, threadCostBuckets.threadCostByDay, 'THREAD_COST');
        expect(
          byDay[threadCostBuckets.mintedDay],
          `today (${threadCostBuckets.mintedDay}) is when every trace_threads row here was really minted; ` +
            'billing the threads there is the regression',
        ).toBeUndefined();
        expect(
          bucketTotal(byDay),
          'thread cost over the window',
        ).toBeCloseTo(threadCostBuckets.current.totalCostUsd, 2);
      });

      await test.step('Trace COST buckets the same money on the other days', async () => {
        // The negative control. Without it, a query that had never stopped
        // reading trace time would satisfy "the series totals $24" just as
        // well — and it also proves the seed itself discriminates, which is
        // what makes the UI assertions below falsifiable.
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'COST',
          interval: DAILY,
          ...window,
        });
        expect(status, `COST rejected with: ${message}`).toBe(200);

        const traceCost = bucketsByDay(series, 'cost');
        expectBucketsByDay(traceCost, threadCostBuckets.traceCostByDay, 'COST');
        expect(
          bucketTotal(traceCost),
          'the two metrics total the same spend, which is what makes their shapes the only discriminator',
        ).toBeCloseTo(threadCostBuckets.current.totalCostUsd, 2);
      });
    },
  );

  test(
    'the Threads Total cost chart asks for THREAD_COST and draws it on the thread days',
    { tag: ['@cap:threads.thread-level-metrics'] },
    async ({ threadCostBuckets, project, backendClient, page }) => {
      const isProjectMetrics = (url: string) => {
        const { pathname } = new URL(url);
        return (
          pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
          pathname === `/api/v1/private/projects/${project.id}/metrics`
        );
      };

      const metricTypeOf = (postData: string | null): string | undefined =>
        (JSON.parse(postData ?? '{}') as { metric_type?: string }).metric_type;

      await test.step('The seeded threads have been materialised', async () => {
        // Before the browser, so a slow ingest fails here — where it reads as
        // what it is — rather than as an empty chart the page is blamed for.
        await expect
          .poll(
            async () => {
              const { status, series } = await backendClient.projectMetric({
                projectId: project.id,
                metricType: 'THREAD_COST',
                interval: DAILY,
                intervalStart: threadCostBuckets.intervalStart,
                intervalEnd: new Date(),
              });
              if (status !== 200) return -1;
              return bucketTotal(bucketsByDay(series, 'thread_cost'));
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBeCloseTo(threadCostBuckets.current.totalCostUsd, 2);
      });

      const logs = new LogsPage(page);

      // The chart's first read, for the count card the page opens on. Armed
      // before the navigation so it cannot be missed, and awaited before the
      // click so the cost read below is not raced by a count read still in
      // flight.
      const countChartRead = page.waitForResponse(
        (r) => isProjectMetrics(r.url()) && metricTypeOf(r.request().postData()) === 'THREAD_COUNT',
        { timeout: 90_000 },
      );

      await test.step('Open Logs in its Threads view over the seeded window', async () => {
        await logs.gotoThreads(project.id, { timeRange: threadCostBuckets.timeRangePreset });
        await expect(
          logs.metricsCardValue('total_cost'),
          'the Total cost card resolves to the seeded spend',
        ).toHaveText(EXPECTED_TOTAL_COST_CARD, { timeout: 60_000 });
        await countChartRead;
      });

      // Anything but the metric the page loaded with. Deliberately not "the
      // request for THREAD_COST": that would be the assertion doing the
      // waiting, and a build still asking for COST would time out here with
      // "no response" instead of failing on the metric type it actually sent.
      // The chart also refetches every 30s, so an unfiltered wait could catch
      // a count refetch and assert against the wrong request.
      const costChartRead = page.waitForResponse(
        (r) => isProjectMetrics(r.url()) && metricTypeOf(r.request().postData()) !== 'THREAD_COUNT',
        { timeout: 60_000 },
      );

      await test.step('Select the Total cost card', async () => {
        await logs.selectMetricsCard('total_cost');
      });

      const response = await costChartRead;

      await test.step('The chart asks for thread cost, not trace cost', async () => {
        const payload = JSON.parse(response.request().postData() ?? '{}') as {
          metric_type?: string;
          interval?: string;
          interval_start?: string;
        };
        expect(
          payload.metric_type,
          'the Threads tab must read THREAD_COST — "COST" here is the regression: trace-level spend under a Threads card',
        ).toBe('THREAD_COST');
        expect(payload.interval, 'the interval past7days implies').toBe(DAILY);
        // Without one the request is refused before any bucketing runs, and
        // the buckets asserted below would be a different query's.
        expect(payload.interval_start, 'the chart sent an interval_start').toEqual(
          expect.any(String),
        );
        expect(response.status(), 'the THREAD_COST read').toBe(200);
      });

      await test.step('The answer it drew is thread_cost, on the thread days', async () => {
        // Read off the page's own traffic rather than asked for again: what is
        // under test is the series the chart was handed, and a second request
        // of our own could differ in window or interval and still agree.
        const series = toMetricSeries(await response.json());
        expect(
          series.map((s) => s.name).sort(),
          'one series, named for thread cost — "cost" would be the trace-level series',
        ).toEqual(['thread_cost']);

        const byDay = bucketsByDay(series, 'thread_cost');
        expectBucketsByDay(byDay, threadCostBuckets.threadCostByDay, 'the rendered chart');
        expect(
          bucketTotal(byDay),
          'the chart sums to the Total cost card above it',
        ).toBeCloseTo(threadCostBuckets.current.totalCostUsd, 2);
      });
    },
  );

  test(
    'the Threads KPI cards put a thread whose traces ran ten days ago in the previous period',
    { tag: ['@cap:threads.thread-level-metrics'] },
    async ({ threadCostBuckets, project, backendClient, page }) => {
      const read = () =>
        backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'threads',
          // The same window the Logs page derives from its `past7days` preset.
          // The page sends no interval_end for a preset ending today, so this
          // does not either — the backend uses its own `now` for both, and it
          // is from that length that it derives the previous period.
          intervalStart: threadCostBuckets.intervalStart,
        });

      await test.step('The seeded threads have been materialised', async () => {
        await expect
          .poll(
            async () => {
              const { status, stats } = await read();
              if (status !== 200) return -1;
              return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(threadCostBuckets.current.threadCount);
      });

      await test.step('The cards split the three threads by when their traces ran', async () => {
        const { status, message, stats } = await read();
        expect(status, `kpi-cards rejected with: ${message}`).toBe(200);
        // The whole answer, not only the cards being compared: the Threads tab
        // renders no error-rate card, and an extra one would mean the endpoint
        // served a shape the page does not expect.
        expect(
          stats.map((s) => s.type).sort(),
          'one card per metric the Threads tab renders, and nothing else',
        ).toEqual(['avg_duration', 'count', 'total_cost']);

        const count = kpi(stats, 'count');
        // The regression in one pair of numbers. All three trace_threads rows
        // were minted today, so a split keyed on the row's id would report
        // 3 / 0 here rather than 2 / 1.
        expect(count.current, 'threads whose traces ran inside the window').toBe(
          threadCostBuckets.current.threadCount,
        );
        expect(count.previous, 'threads whose traces ran in the period before it').toBe(
          threadCostBuckets.previousPeriod.threadCount,
        );

        const cost = kpi(stats, 'total_cost');
        expect(cost.current, 'thread cost this period').toBeCloseTo(
          threadCostBuckets.current.totalCostUsd,
          2,
        );
        expect(cost.previous, 'thread cost last period').toBeCloseTo(
          threadCostBuckets.previousPeriod.totalCostUsd,
          2,
        );
      });

      const logs = new LogsPage(page);

      await test.step('Open Logs in its Threads view over the same window', async () => {
        await logs.gotoThreads(project.id, { timeRange: threadCostBuckets.timeRangePreset });
        // Gates on the count card rather than on the table: the cards are a
        // separate query, and reading the other two before this one resolves
        // would compare against the "N/A" placeholder.
        await expect(
          logs.metricsCardValue('count'),
          'the Threads count card resolves to the seeded count',
        ).toHaveText(EXPECTED_COUNT_CARD, { timeout: 60_000 });
      });

      await test.step('The cards render this period, and the deltas the split implies', async () => {
        // The previous period is not readable off the page at all — only the
        // delta computed from it — which is what makes this the half the API
        // assertions above cannot reach.
        await expect(logs.metricsCardValue('total_cost'), 'the Total cost card').toHaveText(
          EXPECTED_TOTAL_COST_CARD,
        );
        expect(await logs.readMetricsCardDelta('count'), 'the count card delta').toBe(
          EXPECTED_COUNT_DELTA,
        );
        expect(await logs.readMetricsCardDelta('total_cost'), 'the Total cost card delta').toBe(
          EXPECTED_TOTAL_COST_DELTA,
        );
      });
    },
  );
});
