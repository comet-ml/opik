import {
  test,
  expect,
  SERIES_COLORS_TIME_RANGE,
  SERIES_COLORS_WIDGETS,
} from '@e2e/fixtures';
import { DashboardPage } from '@e2e/pom/dashboard.page';

/**
 * How a project-metrics chart resolves the colour of each series.
 *
 * There are two sources, and which one applies depends on whether the widget
 * is grouped:
 *
 *  - **Ungrouped**, the series are the metric's own sub-series (`traces`,
 *    `duration.p50`, …), and each has a fixed colour so the same metric looks
 *    the same everywhere in the product.
 *  - **Grouped**, the series are *group values* — a tag, a trace name — and
 *    their colours come from the tag palette, `hash(label) % palette.length`,
 *    the same way a tag chip is coloured elsewhere.
 *
 * Applying the fixed map to a grouped chart is the bug these tests exist to
 * catch: a group whose value happens to equal a metric key (`traces`, `cost`)
 * takes that metric's colour, so several unrelated groups end up painted
 * identically. Nothing errors and no value is wrong — the chart is simply
 * unreadable, which is why it needs a test rather than a bug report.
 *
 * Both directions are asserted, because the guard can fail either way: not
 * applying it where it belongs would silently drop the fixed colours from
 * every ungrouped chart in the product.
 *
 * The expected colours are the product's palette, pinned as literal hex. That
 * is the point: a spec that recomputed the hash from the palette constants
 * would agree with any palette, including a broken one.
 */
const PALETTE = {
  gray: '#64748b',
  purple: '#8b5cf6',
  purpleDark: '#491b7e',
  burgundy: '#bf399e',
  green: '#10b981',
  turquoise: '#06b6d4',
} as const;

/** Where each seeded tag lands in the tag palette, by its own hash. */
const EXPECTED_GROUP_COLORS: Record<string, string> = {
  traces: PALETTE.green,
  cost: PALETTE.purpleDark,
  beta: PALETTE.purple,
  zeta: PALETTE.turquoise,
  iota: PALETTE.gray,
};

/** The fixed colours an *ungrouped* chart must keep using. */
const METRIC_COLORS = {
  traces: PALETTE.purple,
  'duration.p50': PALETTE.turquoise,
  'duration.p90': PALETTE.burgundy,
  'duration.p99': PALETTE.purple,
} as const;

test.describe('Dashboards — chart series colours', { tag: ['@area:dashboards'] }, () => {
  // Widgets are laid out on a grid; a narrow viewport stacks them and shrinks
  // each chart until recharts drops the legend.
  test.use({ viewport: { width: 1600, height: 1200 } });

  test(
    'A chart grouped by tags colours every group from the tag palette, never from the fixed metric colours',
    { tag: ['@t2-cuj', '@cap:dashboards.configure-widget'] },
    async ({ seriesColorsDashboard, page }) => {
      const dashboard = new DashboardPage(page);
      const { groupedByTag } = SERIES_COLORS_WIDGETS;
      const groups = seriesColorsDashboard.groups;

      await test.step('Open the dashboard and wait for the grouped chart', async () => {
        await dashboard.goto(seriesColorsDashboard.id, SERIES_COLORS_TIME_RANGE);
        await dashboard.waitForChart(groupedByTag, groups.length);
      });

      await test.step('Each group takes the palette colour its own label hashes to', async () => {
        for (const group of groups) {
          expect(await dashboard.legendColor(groupedByTag, group), group).toBe(
            EXPECTED_GROUP_COLORS[group],
          );
        }
      });

      await test.step('The drawn lines carry those same colours, and no two share one', async () => {
        const drawn = await dashboard.seriesColors(groupedByTag);
        // Compared as sets: recharts does not say which curve is which series,
        // and it is the set that has to be right — as many distinct colours as
        // there are groups, and exactly the ones the legend advertises.
        expect(drawn).toHaveLength(groups.length);
        expect(new Set(drawn).size).toBe(groups.length);
        expect([...drawn].sort()).toEqual(
          groups.map((g) => EXPECTED_GROUP_COLORS[g]).sort(),
        );
      });

      await test.step('The groups named after metric keys are not painted the metric colour', async () => {
        // The regression itself: with the fixed map applied, `traces` and
        // `cost` would both be METRIC_COLORS.traces, and so would `beta`,
        // which hashes there — three identical lines in a five-line chart.
        expect(await dashboard.legendColor(groupedByTag, 'traces')).not.toBe(
          METRIC_COLORS.traces,
        );
        expect(await dashboard.legendColor(groupedByTag, 'cost')).not.toBe(
          METRIC_COLORS.traces,
        );
      });
    },
  );

  test(
    'A chart with no grouping keeps the fixed metric colours',
    { tag: ['@t2-cuj', '@cap:dashboards.configure-widget'] },
    async ({ seriesColorsDashboard, page }) => {
      const dashboard = new DashboardPage(page);
      const { ungrouped, duration } = SERIES_COLORS_WIDGETS;

      await test.step('Open the dashboard and wait for both ungrouped charts', async () => {
        await dashboard.goto(seriesColorsDashboard.id, SERIES_COLORS_TIME_RANGE);
        await dashboard.waitForChart(ungrouped, 1);
        await dashboard.waitForChart(duration, 3);
      });

      await test.step('The trace count series keeps its fixed colour, not its hashed one', async () => {
        expect(await dashboard.legendColor(ungrouped, 'traces')).toBe(METRIC_COLORS.traces);
        expect(await dashboard.seriesColors(ungrouped)).toEqual([METRIC_COLORS.traces]);
        // `traces` is seeded as a tag too, so its hashed colour is a real
        // alternative here: seeing it would mean the fixed map had been
        // dropped from ungrouped charts as well as from grouped ones.
        expect(EXPECTED_GROUP_COLORS.traces).not.toBe(METRIC_COLORS.traces);
        expect(await dashboard.legendColor(ungrouped, 'traces')).not.toBe(
          EXPECTED_GROUP_COLORS.traces,
        );
      });

      await test.step('Each duration percentile keeps its own fixed colour', async () => {
        for (const [series, color] of [
          ['duration.p50', METRIC_COLORS['duration.p50']],
          ['duration.p90', METRIC_COLORS['duration.p90']],
          ['duration.p99', METRIC_COLORS['duration.p99']],
        ] as const) {
          expect(await dashboard.legendColor(duration, series), series).toBe(color);
        }

        const drawn = await dashboard.seriesColors(duration);
        expect([...drawn].sort()).toEqual(
          [
            METRIC_COLORS['duration.p50'],
            METRIC_COLORS['duration.p90'],
            METRIC_COLORS['duration.p99'],
          ].sort(),
        );
      });
    },
  );
});
