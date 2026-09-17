import * as fs from 'node:fs/promises';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * How long an export may take to reach the browser as a download. Generous
 * because an unscoped export is one request per 100 rows before the file
 * exists at all, and this budget is a failure message rather than a wait.
 */
const EXPORT_TIMEOUT_MS = 60_000;

/** How long the grid may take to answer a newly-applied filter. */
const FILTER_SETTLE_TIMEOUT_MS = 30_000;

/**
 * The compare view lives at /experiments/{datasetId}/compare?experiments=[...]
 * and renders the SAME page in single- and multi-experiment mode. This POM
 * targets multi-experiment (comparison) mode: two experiments over one dataset.
 *
 * In comparison mode the table is one row per DATASET ITEM. Each cell is
 * vertically split into one band per experiment, ordered by the position of
 * the experiment id in the `experiments` query array — so band index 0 is the
 * first id passed to `goto`, index 1 the second, etc.
 */
export class CompareExperimentsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly datasetId: string,
    private readonly experimentIds: string[],
  ) {}

  private compareUrl(tab: 'items' | 'config' | 'scores'): string {
    const env = loadEnvConfig();
    const experiments = encodeURIComponent(JSON.stringify(this.experimentIds));
    return `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/experiments/${this.datasetId}/compare?experiments=${experiments}&tab=${tab}`;
  }

  async gotoResults(): Promise<void> {
    await test.step('open the compare Results tab', async () => {
      await this.page.goto(this.compareUrl('items'));
    });
  }

  async gotoConfiguration(): Promise<void> {
    await test.step('open the compare Configuration tab', async () => {
      await this.page.goto(this.compareUrl('config'));
    });
  }

  async gotoFeedbackScores(): Promise<void> {
    await test.step('open the compare Feedback scores tab', async () => {
      await this.page.goto(this.compareUrl('scores'));
    });
  }

  async waitForResultsReady(): Promise<void> {
    await test.step('wait for the Results grid to render', async () => {
      await this.compareHeading.waitFor({ state: 'visible' });
      await this.itemRows.first().waitFor({ state: 'visible' });
    });
  }

  async countItemRows(): Promise<number> {
    return this.itemRows.count();
  }

  async expectCompareModeHeader(experimentCount: number): Promise<void> {
    await test.step(`header reads "Compare (${experimentCount})"`, async () => {
      await expect(this.compareHeading).toHaveText(`Compare (${experimentCount})`);
    });
  }

  async expectExperimentNamesInSummary(names: string[]): Promise<void> {
    await test.step('both experiment names appear in the compare summary', async () => {
      const summary = this.compareSummary;
      await expect(summary, 'compare summary row').toBeVisible();
      for (const name of names) {
        await expect(
          summary.getByText(name, { exact: true }),
          `experiment "${name}" named in the compare summary`,
        ).toBeVisible();
      }
    });
  }

  /**
   * The per-experiment score for one shared dataset item. `experimentIndex` is
   * the position of the experiment in the array passed to the constructor,
   * which is the order the split bands render in.
   */
  async readItemScore(datasetItemId: string, experimentIndex: number, metricName: string): Promise<number> {
    return test.step(`read score for item ${datasetItemId} / experiment #${experimentIndex}`, async () => {
      const band = this.splitBand(datasetItemId, experimentIndex, `feedback_scores_${metricName}`);
      await expect(band, `score band for item ${datasetItemId} experiment #${experimentIndex}`).toBeVisible();
      const text = ((await band.textContent()) ?? '').trim();
      const value = parseFloat(text);
      if (Number.isNaN(value)) {
        throw new Error(
          `CompareExperimentsPage.readItemScore: could not parse "${text}" for item ${datasetItemId} experiment #${experimentIndex}`,
        );
      }
      return value;
    });
  }

  /** The per-experiment evaluation-task output for one shared dataset item. */
  async readItemOutput(datasetItemId: string, experimentIndex: number): Promise<string> {
    return test.step(`read output for item ${datasetItemId} / experiment #${experimentIndex}`, async () => {
      const band = this.splitBand(datasetItemId, experimentIndex, 'output_output');
      await expect(band, `output band for item ${datasetItemId} experiment #${experimentIndex}`).toBeVisible();
      return ((await band.textContent()) ?? '').trim();
    });
  }

  /** The aggregate (mean) score for one experiment on the Feedback scores tab. */
  async readAggregateScore(experimentId: string): Promise<number> {
    return test.step(`read aggregate score for experiment ${experimentId}`, async () => {
      const cell = this.page.locator(`td[data-cell-id="0_${experimentId}"]`);
      await expect(cell, `aggregate score cell for experiment ${experimentId}`).toBeVisible();
      const value = parseFloat(((await cell.textContent()) ?? '').trim());
      if (Number.isNaN(value)) {
        throw new Error(`CompareExperimentsPage.readAggregateScore: could not parse a number for ${experimentId}`);
      }
      return value;
    });
  }

  async searchItems(term: string): Promise<void> {
    await test.step(`search the grid for "${term}"`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set('search', term);
      await this.page.goto(url.toString());
      // Wait for the grid to settle on the filtered result: either matching
      // rows, or the explicit no-data row — not the mid-reload empty table.
      await this.page
        .locator('tbody tr[data-row-id], tbody tr[data-testid="no-data-row"]')
        .first()
        .waitFor({ state: 'visible' });
    });
  }

  async openRowPanel(datasetItemId: string): Promise<void> {
    await test.step(`open the detail panel for item ${datasetItemId}`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set('row', datasetItemId);
      await this.page.goto(url.toString());
      // The panel's Close control only exists once the slide-over is mounted.
      await this.page.getByRole('button', { name: 'Close' }).waitFor({ state: 'visible' });
    });
  }

  /**
   * In the row-detail panel each compared experiment is its own section headed
   * by an h2 with the experiment name; assert both the output and score there.
   */
  async expectPanelExperimentResult(
    experimentName: string,
    expected: { output: string; score: number; metricName: string },
  ): Promise<void> {
    await test.step(`panel shows ${experimentName}'s output and score`, async () => {
      const section = this.panelExperimentSection(experimentName);
      await expect(section, `panel section for ${experimentName}`).toBeVisible();
      await expect(section, `${experimentName} output in panel`).toContainText(expected.output);
      const scoreRow = section.locator('tr', { hasText: expected.metricName });
      await expect(scoreRow, `${experimentName} ${expected.metricName} score row`)
        .toContainText(String(expected.score));
    });
  }

  async expectExperimentColumnsInConfiguration(experiments: { id: string; name: string }[]): Promise<void> {
    await test.step('each experiment is a named column on the Configuration tab', async () => {
      for (const exp of experiments) {
        await expect(
          this.configHeader(exp.id),
          `configuration column header for experiment ${exp.id}`,
        ).toContainText(exp.name);
      }
    });
  }

  /**
   * The score column header is a sticky, overlay-covered element that a direct
   * click can't reliably hit; the grid instead reads sort state from the
   * `sorting` query param (the same the header click writes). Driving sort via
   * the URL exercises the real server-side sort path deterministically and
   * still asserts on the rendered row order.
   */
  async sortByScoreDescending(metricName: string): Promise<void> {
    await this.sortByColumn(`feedback_scores_${metricName}`, 'desc');
  }

  /**
   * Sorts the grid by an arbitrary column id, in either direction.
   *
   * `columnId` is the id the table uses in its own `sorting` state — the same
   * value a header click writes — so a dynamic JSON column is addressed exactly
   * as the grid addresses it: `output.<key>`, `data.<key>`, `metadata.<key>`.
   * The front end maps that id to the backend `sorting` field on the wire, so
   * driving the query param exercises the real serialise → sort → render path,
   * including the `+`-encoding of a key containing a space.
   *
   * Driven through the URL rather than by clicking the header for the reason
   * given on sortByScoreDescending above: the header is sticky and overlaid by
   * the statistics sub-row, so a click lands unreliably.
   */
  async sortByColumn(columnId: string, direction: 'asc' | 'desc'): Promise<void> {
    await test.step(`sort the grid by "${columnId}" ${direction}ending`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set(
        'sorting',
        JSON.stringify([{ id: columnId, desc: direction === 'desc' }]),
      );
      await this.page.goto(url.toString());
      await this.itemRows.first().waitFor({ state: 'visible' });
    });
  }

  /** Dataset-item ids in current row order, top to bottom. */
  async itemRowOrder(): Promise<string[]> {
    return test.step('read the current row order', async () => {
      const ids = await this.itemRows.evaluateAll((rows) =>
        rows.map((r) => r.getAttribute('data-row-id') ?? ''),
      );
      return ids;
    });
  }

  /**
   * Add one filter through the FiltersButton popover, and return the row total
   * the grid received for it.
   *
   * Driven through the real popover rather than the `filters` query param —
   * unlike `sorting`, the filter's wire shape is assembled by the control
   * itself (column id, type and the operator it defaults to), so a spec that
   * hand-wrote the param would be asserting its own guess at that shape rather
   * than the one a user produces.
   *
   * Two things this has to get right, both of which cost an exploration pass:
   *  - the popover COMMITS on click-outside and DISCARDS on Escape, so
   *    dismissing it the obvious way leaves the view unfiltered — and a spec
   *    that then exported would assert happily against 250 rows;
   *  - the filter applies live as the value is typed, so the settle point is
   *    the grid's own data response carrying the final value, not the popover
   *    closing.
   *
   * The total comes from that response because the grid is virtualised: only
   * the rows in view carry a `data-row-id`, so counting the DOM would report
   * the viewport, not the result set.
   */
  async addGridFilter(column: string, value: string): Promise<number> {
    return test.step(`filter the grid where "${column}" contains "${value}"`, async () => {
      await this.filtersButton.click();

      const columnSelect = this.page.locator(
        'button[role="combobox"]:has([data-testid="filter-column"])',
      );
      await columnSelect.click();
      await this.page.getByRole('option', { name: column, exact: true }).click();

      // The grid's own row read, not one of the sibling column/statistics calls
      // that carry the same `filters` param and answer without a `total` —
      // hence pathname equality rather than a substring match.
      const settled = this.page.waitForResponse(
        (response) =>
          new URL(response.url()).pathname.endsWith('/items/experiments/items') &&
          decodeURIComponent(response.url()).includes(`"value":"${value}"`) &&
          response.ok(),
        { timeout: FILTER_SETTLE_TIMEOUT_MS },
      );

      await this.page.locator('input[placeholder="value"]').fill(value);
      // Commit by clicking outside. The page heading is inert and always present.
      await this.compareHeading.click();

      const body: unknown = await (await settled).json();
      const total = (body as { total?: unknown }).total;
      if (typeof total !== 'number') {
        throw new Error(
          'CompareExperimentsPage.addGridFilter: the filtered read answered without a total — ' +
            'cannot tell a narrowed result set from an unfiltered one.',
        );
      }
      await this.page
        .locator('tbody tr[data-row-id], tbody tr[data-testid="no-data-row"]')
        .first()
        .waitFor({ state: 'visible' });
      return total;
    });
  }

  /**
   * How many rows the grid currently has in the DOM.
   *
   * The table is virtualised, so this is the viewport rather than the result
   * set — only assert on it when the view has been narrowed to fewer rows than
   * a screen holds.
   */
  async expectRenderedRowCount(expected: number): Promise<void> {
    await test.step(`the grid renders ${expected} row(s)`, async () => {
      await expect(this.itemRows, 'rendered comparison rows').toHaveCount(expected);
    });
  }

  /** Tick the select checkbox on each named row, addressed by dataset-item id. */
  async selectRows(datasetItemIds: string[]): Promise<void> {
    await test.step(`select ${datasetItemIds.length} row(s)`, async () => {
      for (const id of datasetItemIds) {
        const checkbox = this.rowCheckbox(id);
        await expect(checkbox, `select checkbox for row ${id}`).toHaveCount(1);
        await checkbox.click();
        await expect(checkbox, `row ${id} after ticking`).toBeChecked();
      }
    });
  }

  /** Untick the select checkbox on each named row. */
  async deselectRows(datasetItemIds: string[]): Promise<void> {
    await test.step(`deselect ${datasetItemIds.length} row(s)`, async () => {
      for (const id of datasetItemIds) {
        const checkbox = this.rowCheckbox(id);
        await expect(checkbox, `select checkbox for row ${id}`).toHaveCount(1);
        await checkbox.click();
        await expect(checkbox, `row ${id} after unticking`).not.toBeChecked();
      }
    });
  }

  async expectExportEnabled(): Promise<void> {
    await test.step('the export control is offered', async () => {
      await expect(this.exportButton, 'export button').toHaveCount(1);
      await expect(this.exportButton, 'export button').toBeEnabled();
    });
  }

  /**
   * Export the current view as JSON and return the parsed file.
   *
   * The file, not the page: the export is built in the tab and handed to the
   * browser as a download, so the only way to tell "the file holds every row"
   * from "the file holds the page on screen" is to read the bytes on disk.
   *
   * With nothing selected the export reads the whole result set page by page,
   * so the click can outlive the default action budget on a large comparison.
   */
  async exportAsJson(): Promise<Record<string, unknown>[]> {
    return test.step('export the view as JSON and read the downloaded file', async () => {
      await this.exportButton.click();
      const menuItem = this.page.getByRole('menuitem', { name: 'Export as JSON' });
      await expect(menuItem, 'Export as JSON menu item').toBeEnabled();

      const [download] = await Promise.all([
        this.page.waitForEvent('download', { timeout: EXPORT_TIMEOUT_MS }),
        menuItem.click(),
      ]);

      const path = await download.path();
      const parsed: unknown = JSON.parse(await fs.readFile(path, 'utf-8'));
      if (!Array.isArray(parsed)) {
        throw new Error(
          `CompareExperimentsPage.exportAsJson: expected an array, got ${typeof parsed}`,
        );
      }
      return parsed as Record<string, unknown>[];
    });
  }

  /** The rendered text of one dataset column's cell — what the user actually sees. */
  async readDatasetCellText(datasetItemId: string, field: string): Promise<string> {
    return test.step(`read the on-screen "${field}" cell for item ${datasetItemId}`, async () => {
      // TanStack derives a column id from the accessor key by replacing dots,
      // so the `data.detail` column is addressed as `data_detail` here while
      // `sorting` and `filters` still take `data.detail` (see sortByColumn).
      const cell = this.page.locator(
        `td[data-cell-id="${datasetItemId}_data_${field}"]`,
      );
      await expect(cell, `"${field}" cell for item ${datasetItemId}`).toHaveCount(1);
      return ((await cell.textContent()) ?? '').trim();
    });
  }

  /**
   * The export trigger, addressed by its Download icon.
   *
   * No `data-testid` and no accessible name: the control is an icon-only button
   * whose only label is a hover tooltip, which contributes nothing to the
   * accessibility tree. A `data-testid` belongs on `ExportToButton` — it is not
   * added here because these specs are verified against a pre-built deployment
   * of the PR under test, where a front-end attribute added alongside them
   * would not exist. The count assertion in `expectExportEnabled` keeps the
   * match honest if a second download control ever joins the panel.
   */
  private get exportButton(): Locator {
    return this.page.locator('button:has(svg.lucide-download)');
  }

  /** The FiltersButton trigger — icon-only, labelled only by a hover tooltip. */
  private get filtersButton(): Locator {
    return this.page.locator('button:has(svg.lucide-filter)');
  }

  private rowCheckbox(datasetItemId: string): Locator {
    return this.page
      .locator(`tbody tr[data-row-id="${datasetItemId}"]`)
      .getByRole('checkbox', { name: 'Select row' });
  }

  private get compareHeading(): Locator {
    return this.page.getByRole('heading', { level: 1 });
  }

  /** The "Baseline of X compared against Y" summary row (compare mode only). */
  private get compareSummary(): Locator {
    return this.page.locator('div').filter({ hasText: /^Baseline of/ }).last();
  }

  private get itemRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * A per-experiment band inside a vertically-split grid cell. `columnId` is the
   * table column id (e.g. `feedback_scores_equals_metric`, `output_output`);
   * `experimentIndex` is the experiment's position in the `experiments` query
   * array, which is the order the bands render in.
   */
  private splitBand(datasetItemId: string, experimentIndex: number, columnId: string): Locator {
    const cell = this.page.locator(`td[data-cell-id="${datasetItemId}_${columnId}"]`);
    return cell.locator(`div[data-virtual-row-id="${datasetItemId}-${experimentIndex}"]`);
  }

  private configHeader(experimentId: string): Locator {
    return this.page.locator(`th[data-header-id="${experimentId}"]`);
  }

  /** A compared experiment's section in the row-detail panel, keyed by its h2 name. */
  private panelExperimentSection(experimentName: string): Locator {
    return this.page
      .getByRole('heading', { level: 2, name: experimentName })
      .locator('xpath=ancestor::*[.//table][1]');
  }
}
