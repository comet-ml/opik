import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * A workspace dashboard — the grid of widgets at
 * `/<workspace>/dashboards/<dashboardId>`.
 *
 * Everything here is about the *colours* a chart widget draws, because that is
 * the part of a chart a user reads without any text: two series painted the
 * same colour is a chart that is silently wrong. Two independent renderings of
 * the same resolved colour are exposed, and a spec should assert both — the
 * legend swatch (what the user matches a name against) and the SVG series
 * stroke (what the user actually traces with their eye). They come from the
 * same chart config, so agreeing is not interesting; disagreeing would be.
 *
 * Colours are read as *computed* values and normalised to hex rather than read
 * off the `stroke`/`--bg-color` attribute. The attribute holds a CSS variable
 * (`var(--color-purple)`), so asserting on it would pin the variable name and
 * pass happily if the variable itself were redefined to another colour.
 *
 * Selector note: neither the widget card nor the legend carries a
 * `data-testid`, so widgets are addressed by their (spec-controlled) title and
 * legend entries by their label. `.react-grid-item` and the recharts curve
 * classes are library-owned class names. A `data-testid` on the widget card
 * and on the legend colour indicator would let this drop the CSS entirely.
 */
export class DashboardPage {
  constructor(private readonly page: Page) {}

  /**
   * Opens a dashboard with its date range pinned on the URL. The range is
   * otherwise remembered in localStorage, so leaving it unset would let one
   * run's choice decide another run's chart.
   */
  async goto(dashboardId: string, timeRange: string): Promise<void> {
    return test.step(`Open dashboard ${dashboardId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/dashboards/${dashboardId}?dashboard_time_range=${timeRange}`,
      );
    });
  }

  /**
   * Waits until a widget has drawn `seriesCount` series, and asserts it drew
   * exactly that many. Charts render their axes before the data arrives, so
   * waiting on the chart alone would let a spec read an empty legend.
   */
  async waitForChart(widgetTitle: string, seriesCount: number): Promise<void> {
    return test.step(`Wait for "${widgetTitle}" to draw ${seriesCount} series`, async () => {
      const widget = this.widget(widgetTitle);
      await expect(widget).toHaveCount(1);
      await expect(widget.locator('.recharts-surface')).toBeVisible();
      await expect(this.legendSwatches(widgetTitle)).toHaveCount(seriesCount);
      await expect(this.seriesCurves(widgetTitle)).toHaveCount(seriesCount);
    });
  }

  /** The card of the widget whose header reads exactly `title`. */
  widget(title: string): Locator {
    return this.page
      .locator('.react-grid-item')
      .filter({ has: this.page.getByText(title, { exact: true }) });
  }

  /**
   * The colour of a legend entry's swatch, as `#rrggbb`.
   *
   * The entry is addressed by its label, anchored and exact: a substring match
   * on `cost` would also match a group named `cost-usd`.
   */
  async legendColor(widgetTitle: string, label: string): Promise<string> {
    return test.step(`Read the "${label}" legend colour of "${widgetTitle}"`, async () => {
      const swatch = this.legendItem(widgetTitle, label).locator(LEGEND_SWATCH);
      await expect(swatch).toHaveCount(1);
      return toHex(await swatch.evaluate((el) => getComputedStyle(el).backgroundColor));
    });
  }

  /**
   * The stroke colour of every series line the widget drew, as `#rrggbb`, in
   * DOM order.
   *
   * Deliberately the whole set rather than a lookup per series: recharts does
   * not label a rendered curve with the series it belongs to, and the property
   * this is here to protect is a property of the set — that no two series share
   * a colour. A per-series read would not be able to see a collision at all.
   */
  async seriesColors(widgetTitle: string): Promise<string[]> {
    return test.step(`Read the series colours of "${widgetTitle}"`, async () => {
      const strokes = await this.seriesCurves(widgetTitle).evaluateAll((els) =>
        els.map((el) => getComputedStyle(el).stroke),
      );
      return strokes.map(toHex);
    });
  }

  private legendItem(widgetTitle: string, label: string): Locator {
    return this.widget(widgetTitle)
      .locator(`div:has(> ${LEGEND_SWATCH})`)
      .filter({ hasText: new RegExp(`^\\s*${escapeRegExp(label)}\\s*$`) });
  }

  private legendSwatches(widgetTitle: string): Locator {
    return this.widget(widgetTitle).locator(LEGEND_SWATCH);
  }

  /**
   * A single-series chart is drawn as a filled area and a multi-series one as
   * lines, so both curve classes count as "a series".
   */
  private seriesCurves(widgetTitle: string): Locator {
    return this.widget(widgetTitle).locator(
      '.recharts-line-curve, .recharts-area-curve',
    );
  }
}

/**
 * The chart legend's colour dot. It carries no testid and no text; what marks
 * it out is the inline custom property the resolved series colour is written
 * to, which is also what its background renders from.
 */
const LEGEND_SWATCH = 'div[style*="--bg-color"]';

/** `rgb(16, 185, 129)` -> `#10b981`. Anything else is returned unchanged. */
function toHex(color: string): string {
  const match = color.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
  if (!match) return color;
  return `#${match
    .slice(1, 4)
    .map((v) => Number(v).toString(16).padStart(2, '0'))
    .join('')}`;
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
