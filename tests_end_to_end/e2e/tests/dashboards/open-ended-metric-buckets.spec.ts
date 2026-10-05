import { test, expect } from '@e2e/fixtures';
import type { MetricSeries, ProjectMetricType } from '@e2e/core/backend';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * A project metrics series read with NO `interval_end` — the window every
 * date-range preset that ends today produces, and so the window the default
 * dashboard and the default Logs chart both read at (OPIK-8206).
 *
 * Two things change on that branch, and neither is reachable with an explicit
 * end. There is no upper id bound, so a row whose UUIDv7 id instant lies in the
 * future counts; and the bucket expression clamps to the fill end
 * (`least(id time, fillTo)`), so that row lands in the LATEST bucket instead of
 * reaching `toStartOfInterval`'s wrapping range. `WITH FILL` is now emitted
 * here too, so the answer is a complete frame rather than the sparse one an
 * open-ended read used to return.
 *
 * Nothing in the estate crossed this axis: all three sibling
 * `dashboards/*metrics*.spec.ts` specs pass an explicit `intervalEnd:
 * new Date()`, so `metric-date-range`'s `covered: true` said nothing about the
 * branch the product actually defaults to.
 *
 * The contrast is what pins it, and the seed is built for one specific
 * confusion: the far-future row's `start_time` falls on a day INSIDE the
 * requested window, four days back, while its id does not. So a read that
 * ranged or bucketed on `start_time` — which is what the workspace legs did
 * before this release — would count the row under an explicit end too, and
 * would place it on that day rather than in the latest bucket. Both readings
 * are ruled out below.
 *
 * SCOPE — the same two layers as the neighbouring dashboards specs. The API
 * tests pin the frame and the bucketing, neither of which can be read back off
 * a chart. The last test drives a real Time series widget at a real preset, so
 * the capability tag names a window a browser actually asked for.
 */

/** The four count/cost metrics the Logs chart and a Time series widget plot. */
const METRICS: Array<{ metricType: ProjectMetricType; series: string; perRow: number }> = [
  { metricType: 'TRACE_COUNT', series: 'traces', perRow: 1 },
  { metricType: 'SPAN_COUNT', series: 'spans', perRow: 1 },
  { metricType: 'THREAD_COUNT', series: 'threads', perRow: 1 },
  // One priced LLM span per row at exactly $6.00; see the fixture.
  { metricType: 'COST', series: 'cost', perRow: 6 },
];

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * One named series' points keyed by the UTC day its bucket starts on.
 *
 * The series must be present: an absent one means the aggregation returned
 * nothing, which is a failure rather than a frame of zeroes. A `WITH FILL`
 * padding point carries `null`, and that genuinely is zero.
 */
function bucketsByDay(series: MetricSeries[], name: string): Map<string, number> {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  const byDay = new Map<string, number>();
  for (const point of found!.points) {
    const day = point.time.slice(0, 10);
    expect(byDay.has(day), `one DAILY bucket per day, but "${day}" appears twice`).toBe(false);
    byDay.set(day, point.value ?? 0);
  }
  return byDay;
}

/** The day the frame's last bucket starts on — where an open-ended read clamps. */
function latestDay(byDay: Map<string, number>): string {
  const days = [...byDay.keys()];
  expect(days.length, 'the frame carries at least one bucket').toBeGreaterThan(0);
  return days[days.length - 1];
}

test.describe(
  'Open-ended project metric buckets — data contract',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    /**
     * The fixture writes six traces and six spans and the reads poll for
     * ingestion, which the default budget cannot contain. Declared on the
     * describe so it covers fixture setup too.
     */
    test.slow();

    test(
      'an open-ended DAILY read returns a filled frame and clamps the far-future row into its latest bucket',
      { tag: ['@cap:dashboards.metric-date-range'] },
      async ({ openEndedWindowRows, project, backendClient }) => {
        const { current, previous, farFuture, intervalStart } = openEndedWindowRows;

        const read = (metricType: ProjectMetricType, withEnd: boolean) =>
          backendClient.projectMetric({
            projectId: project.id,
            metricType,
            interval: 'DAILY',
            intervalStart,
            // The branch under test: the front end sends nothing here for a
            // preset ending today, and `new Date()` is the other branch.
            ...(withEnd ? { intervalEnd: new Date() } : {}),
          });

        await test.step('The seeded rows are queryable', async () => {
          // Ingestion is eventually consistent; poll rather than sleep.
          await expect
            .poll(
              async () => {
                const { status, series } = await read('TRACE_COUNT', false);
                if (status !== 200) return -1;
                const found = series.find((s) => s.name === 'traces');
                return found ? found.points.reduce((acc, p) => acc + (p.value ?? 0), 0) : 0;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            // Three current rows plus the far-future one, which an open-ended
            // read counts; the two prior-period rows are outside the window.
            .toBe(current.length + 1);
        });

        for (const { metricType, series: seriesName, perRow } of METRICS) {
          await test.step(`${metricType}: the open-ended frame is complete and ends at the latest bucket`, async () => {
            const { status, message, series } = await read(metricType, false);
            expect(status, `${metricType} rejected with: ${message}`).toBe(200);

            const byDay = bucketsByDay(series, seriesName);

            // A complete frame, which is the half `WITH FILL` provides: every
            // UTC day from the requested start through the latest bucket is
            // present exactly once and the steps are one day. Before the
            // release an open-ended read answered with a sparse frame, so a
            // chart drew a line through the gaps.
            const days = [...byDay.keys()];
            expect(days[0], `${metricType}: the frame starts at the requested day`).toBe(
              intervalStart.toISOString().slice(0, 10),
            );
            expect(days, `${metricType}: the frame is in ascending day order`).toEqual(
              [...days].sort(),
            );
            const steps = new Set(
              days
                .slice(1)
                .map((d, i) => Date.parse(`${d}T00:00:00Z`) - Date.parse(`${days[i]}T00:00:00Z`)),
            );
            expect(steps, `${metricType}: every gap between consecutive buckets`).toEqual(
              new Set([DAY_MS]),
            );

            // Each dated row sits in its own day's bucket. Derived from the
            // minted id rather than from a literal, so the assertion survives
            // the run starting at any hour.
            for (const row of current) {
              expect(
                byDay.get(row.idDay),
                `${metricType}: the bucket for the row whose id is on ${row.idDay}`,
              ).toBe(perRow);
            }

            // The far-future row is in the LATEST bucket, not in its own
            // 2200 one and not in the bucket its start_time falls on.
            const latest = latestDay(byDay);
            expect(latest, `${metricType}: the latest bucket is not a seeded day`).not.toBe(
              farFuture.startDay,
            );
            expect(
              byDay.get(latest),
              `${metricType}: the latest bucket carries the far-future row`,
            ).toBe(perRow);
            expect(
              byDay.get(farFuture.startDay),
              `${metricType}: nothing lands on the far-future row's start_time day`,
            ).toBe(0);

            // And nothing anywhere else. The other half of the assertion, and
            // the half the per-day lookups alone would miss: a query that
            // ignored its bucket expression would pile everything into one
            // bucket and still satisfy some of them.
            const seeded = new Set([...current.map((r) => r.idDay), latest]);
            const strays = [...byDay.entries()].filter(([d, v]) => !seeded.has(d) && v !== 0);
            expect(strays, `${metricType}: buckets outside the seeded days are empty`).toEqual([]);
            // The prior-period rows are outside the requested window entirely,
            // so a dropped interval_start would show up here.
            for (const row of previous) {
              expect(
                byDay.has(row.idDay),
                `${metricType}: the frame does not reach the prior period (${row.idDay})`,
              ).toBe(false);
            }
          });

          await test.step(`${metricType}: the same read with interval_end = now drops it entirely`, async () => {
            const openEnded = bucketsByDay((await read(metricType, false)).series, seriesName);
            const { status, message, series } = await read(metricType, true);
            expect(status, `${metricType} with an explicit end rejected with: ${message}`).toBe(
              200,
            );
            const bounded = bucketsByDay(series, seriesName);

            // Zero, not re-bucketed. The row's start_time is inside the
            // window, so a start_time-ranged read would still count it here —
            // which is exactly the disagreement between endpoints the release
            // removed.
            expect(
              bounded.get(latestDay(bounded)),
              `${metricType}: the latest bucket is empty under an explicit end`,
            ).toBe(0);
            expect(
              bounded.get(farFuture.startDay),
              `${metricType}: and it did not move to its start_time day either`,
            ).toBe(0);

            // Every earlier bucket is untouched, so the contrast is the
            // far-future row alone rather than a differently-shaped query.
            //
            // Each frame's own latest bucket is excluded, not just this one's:
            // that is where the far-future row clamps, so the two legitimately
            // differ there — and if UTC midnight passes between the two reads
            // the later frame gains a bucket, which moves where the earlier
            // one's clamp sits. Everything before both is a like-for-like
            // comparison at every hour the suite could run at.
            const boundedLatest = latestDay(bounded);
            const openEndedLatest = latestDay(openEnded);
            const compared = [...bounded.keys()].filter(
              (day) => day !== boundedLatest && day !== openEndedLatest,
            );
            // Guards the loop itself: an empty comparison would pass silently,
            // and the seed puts three dated rows inside this window.
            expect(
              compared.length,
              `${metricType}: buckets compared between the two reads`,
            ).toBeGreaterThanOrEqual(current.length);
            for (const day of compared) {
              expect(
                bounded.get(day),
                `${metricType}: the ${day} bucket is the same under both reads`,
              ).toBe(openEnded.get(day) ?? 0);
            }
          });
        }
      },
    );

    test(
      'a Time series widget at a preset ending today reads open-ended and still renders its chart',
      {
        tag: [
          '@cap:dashboards.metric-date-range',
          '@cap:dashboards.create-dashboard',
          '@cap:dashboards.add-widget',
          // The widget is scoped to one project and given its metric through
          // the widget dialog before its range is read at all — the same
          // assertions project-span-metrics files under this key. Untagged it
          // would be coverage the map cannot see.
          '@cap:dashboards.configure-widget',
        ],
      },
      async ({ openEndedWindowRows, project, backendClient, registerDashboardCleanup, page }) => {
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
                  metricType: 'SPAN_COUNT',
                  interval: 'DAILY',
                  intervalStart: openEndedWindowRows.intervalStart,
                });
                if (status !== 200) return -1;
                const found = series.find((s) => s.name === 'spans');
                return found ? found.points.reduce((acc, p) => acc + (p.value ?? 0), 0) : 0;
              },
              { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
            )
            .toBe(openEndedWindowRows.current.length + 1);
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
          (r) => {
            const { pathname } = new URL(r.url());
            return (
              pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
              pathname === `/api/v1/private/projects/${project.id}/metrics`
            );
          },
          { timeout: 60_000 },
        );

        await test.step('Configure the widget: this project, Span token usage', async () => {
          await dashboards.addProjectSpanTokenUsageWidget(project.name);
          // The default preset, and the reason this test is about the
          // open-ended branch at all: every preset the control offers ends
          // today, so `calculateIntervalConfig` returns `intervalEnd:
          // undefined` for all of them. Stated so a changed default cannot
          // quietly turn this into an explicit-end test that still passes.
          expect(await dashboards.selectedDateRange(), 'the default preset').toBe('Past 30 days');
        });

        await test.step('The widget asked for an open-ended window and was served', async () => {
          const response = await metricsRead;
          const payload = JSON.parse(response.request().postData() ?? '{}') as Record<
            string,
            unknown
          >;
          expect(payload.interval, 'the interval the widget asked for').toBe('DAILY');
          expect(payload.interval_start, 'the widget sent an interval_start').toEqual(
            expect.any(String),
          );
          // What a browser cannot otherwise be shown to have done. The front
          // end omits the key entirely rather than sending null, and the
          // backend branches on its absence.
          expect(payload, 'the widget sent no interval_end').not.toHaveProperty('interval_end');
          // A mis-typed WITH FILL bound surfaces here as a 500 while the chart
          // simply stays blank.
          expect(response.status(), 'the open-ended metrics read').toBe(200);
        });

        await test.step('The widget renders the series rather than the empty state', async () => {
          // The chart plots points as SVG geometry, so the rendered pixels
          // cannot be compared to a token count — that is what the data
          // contract test above is for. What the UI must prove is that the
          // widget resolved its open-ended query and drew the answer.
          expect(
            await dashboards.widgetSeriesNames(widgetTitle),
            'the chart legend names the selected usage series',
          ).toContain('total_tokens');
        });
      },
    );
  },
);
