import { expect as baseExpect } from '@playwright/test';
import { test as baseTest } from './filterable-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** Widget titles, which are also how a spec addresses a widget on the page. */
export const SERIES_COLORS_WIDGETS = {
  groupedByTag: 'Traces grouped by tag',
  ungrouped: 'Traces with no grouping',
  duration: 'Trace duration percentiles',
} as const;

/**
 * Tag values carried by the seeded traces, one per trace, so each becomes its
 * own group when the chart is grouped by Tags.
 *
 * `traces` and `cost` are chosen deliberately: they collide with keys of the
 * chart's fixed metric colour map, which is what makes them able to detect a
 * grouped chart resolving group colours through that map instead of through
 * the tag palette. The rest are ordinary values that hash to distinct slots.
 */
export const SERIES_COLORS_TAGS = ['traces', 'cost', 'beta', 'zeta', 'iota'] as const;

/**
 * Ages, in days, of the traces seeded per tag. Three points spread over three
 * days so a DAILY chart draws an actual line per group: recharts renders no
 * line curve at all for a single-point series, only a dot.
 */
const TRACE_AGES_DAYS = [0.2, 1.2, 2.2];

/** Distinct durations so the p50/p90/p99 series are not the same number. */
const TRACE_DURATIONS_SECONDS = [0.5, 2, 8];

/**
 * The window the spec pins on the dashboard URL. Wide enough to hold every
 * seeded trace, and > 3 days so the chart buckets DAILY rather than HOURLY.
 */
export const SERIES_COLORS_TIME_RANGE = 'past7days';
const TIME_RANGE_DAYS = 7;

export interface SeriesColorsDashboardRef {
  /** The dashboard holding the three widgets, ready to open. */
  id: string;
  name: string;
  /** Group values present on the grouped chart, one per seeded tag. */
  groups: string[];
}

export interface SeriesColorsDashboardFixtures {
  seriesColorsDashboard: SeriesColorsDashboardRef;
}

/**
 * A project of tagged traces plus a dashboard whose widgets read it, seeded
 * entirely through the API so the browser only ever has to *look* at charts.
 *
 * The three widgets cover both sides of the "is a fixed metric colour map
 * applied?" question on one page: one chart grouped by Tags (colours must come
 * from the tag palette), and two ungrouped ones (colours must come from the
 * fixed map).
 *
 * The widgets are configured in the created config rather than through the
 * widget editor. That keeps the spec's subject the rendering, not the editor —
 * and it avoids the editor's own default of switching a newly grouped widget
 * to Total aggregation, which collapses every series to a single point.
 */
export const test = baseTest.extend<SeriesColorsDashboardFixtures>({
  seriesColorsDashboard: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    for (const tag of SERIES_COLORS_TAGS) {
      for (const [index, ageDays] of TRACE_AGES_DAYS.entries()) {
        await sdkClient.python.createNestedTrace({
          project_name: project.name,
          name: `${testNamespace}-${tag}-${index}`,
          input: { query: tag },
          output: { answer: tag },
          tags: [tag],
          age_days: ageDays,
          duration_seconds: TRACE_DURATIONS_SECONDS[index],
          spans: [],
        });
      }
    }

    // Prove the seed really produced one group per tag before anything opens a
    // browser. Without this a chart that renders nothing — because ingestion
    // had not landed, or because the grouping silently returned a single
    // series — would make the UI assertions unreachable rather than failing.
    const window = {
      intervalStart: new Date(Date.now() - TIME_RANGE_DAYS * 24 * 60 * 60 * 1000),
      intervalEnd: new Date(),
    };
    await baseExpect
      .poll(
        async () => {
          const series = await backendClient.getProjectMetricSeries({
            projectId: project.id,
            metricType: 'TRACE_COUNT',
            interval: 'DAILY',
            breakdownField: 'tags',
            ...window,
          });
          return series.map((s) => s.name).sort();
        },
        { timeout: 60_000 },
      )
      .toEqual([...SERIES_COLORS_TAGS].sort());

    const widget = (id: string, title: string, config: Record<string, unknown>) => ({
      id,
      title,
      type: 'project_metrics',
      config: { projectId: project.id, chartType: 'line', traceFilters: [], ...config },
    });

    const created = await backendClient.createDashboard({
      name: `${testNamespace}-dash`,
      type: 'multi_project',
      config: {
        version: 4,
        sections: [
          {
            id: 'series-colors',
            title: 'Series colours',
            widgets: [
              widget('w-grouped', SERIES_COLORS_WIDGETS.groupedByTag, {
                metricType: 'TRACE_COUNT',
                breakdown: { field: 'tags' },
              }),
              widget('w-ungrouped', SERIES_COLORS_WIDGETS.ungrouped, {
                metricType: 'TRACE_COUNT',
                breakdown: { field: 'none' },
              }),
              widget('w-duration', SERIES_COLORS_WIDGETS.duration, {
                metricType: 'DURATION',
              }),
            ],
            layout: [
              { i: 'w-grouped', x: 0, y: 0, w: 3, h: 5 },
              { i: 'w-ungrouped', x: 3, y: 0, w: 3, h: 5 },
              { i: 'w-duration', x: 0, y: 5, w: 3, h: 5 },
            ],
          },
        ],
        lastModified: 0,
      },
    });

    await testInfo.attach('opik.seriesColorsDashboard', {
      body: JSON.stringify({ id: created.id, name: created.name }, null, 2),
      contentType: 'application/json',
    });

    await use({ id: created.id, name: created.name, groups: [...SERIES_COLORS_TAGS] });

    // The traces cascade with the project fixture's own teardown; the
    // dashboard does not belong to the project and is outside the run-prefix
    // sweep, so it has to be deleted here.
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDashboard(created.id);
      } catch (err) {
        console.warn(`[seriesColorsDashboard fixture] delete warning for ${created.name}:`, err);
      }
    }
  },
});

export { expect } from './filterable-traces.fixture';
