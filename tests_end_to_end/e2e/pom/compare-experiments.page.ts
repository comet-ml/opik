import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

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

  /**
   * The compared experiments' section headings in the row-detail panel, in DOM
   * order — which, inside the panel's horizontal flex group, is left-to-right
   * reading order.
   *
   * Asserted with `toHaveText(names)` rather than read-then-compare so the
   * assertion retries while the panel is still populating and fails on a count
   * mismatch as well as on a wrong order. `panelExperimentSection()` is keyed by
   * name and so cannot express order at all.
   */
  async expectPanelExperimentOrder(experimentNames: string[]): Promise<void> {
    await test.step(`panel sections read ${JSON.stringify(experimentNames)} left to right`, async () => {
      await expect(
        this.rowPanel.getByRole('heading', { level: 2 }),
        'compared experiment sections, in panel order',
      ).toHaveText(experimentNames);
    });
  }

  /**
   * How many resize dividers the row-detail panel's layout has. One fewer than
   * the number of panels (dataset + one per compared experiment), so a
   * two-experiment comparison has two.
   */
  async countPanelDividers(): Promise<number> {
    return this.panelDividers.count();
  }

  /**
   * The `data-panel-size` of every panel in the row-detail panel's resizable
   * group, in DOM order.
   *
   * Deliberately the group's own percentages, not measured widths: the panels
   * carry a `min-w-72` CSS floor with no matching `minSize` prop, so a narrow
   * panel's rendered width clamps and stops tracking the size the group
   * assigned it. The percentages are what the layout actually persists.
   */
  async panelLayout(): Promise<string[]> {
    return test.step('read the row-detail panel layout', async () => {
      return this.panelsInRowPanel.evaluateAll((panels) =>
        panels.map((p) => p.getAttribute('data-panel-size') ?? ''),
      );
    });
  }

  /**
   * Drags one of the row-detail panel's resize dividers horizontally and
   * returns the layout it settles on.
   *
   * `dividerIndex` is positional because a divider has no identity beyond where
   * it sits between two panels; call `countPanelDividers()` first so the
   * position is unambiguous. Waits for the layout to actually change, so the
   * returned value is the post-drag one rather than a mid-drag read.
   */
  async dragPanelDivider(dividerIndex: number, deltaX: number): Promise<string[]> {
    return test.step(`drag panel divider #${dividerIndex} by ${deltaX}px`, async () => {
      const before = await this.panelLayout();
      const divider = this.panelDividers.nth(dividerIndex);
      await expect(divider, `panel divider #${dividerIndex}`).toBeVisible();
      const box = await divider.boundingBox();
      if (!box) {
        throw new Error(`CompareExperimentsPage.dragPanelDivider: divider #${dividerIndex} has no bounding box`);
      }

      const y = box.y + box.height / 2;
      const startX = box.x + box.width / 2;
      await this.page.mouse.move(startX, y);
      await this.page.mouse.down();
      await this.page.mouse.move(startX + deltaX, y, { steps: 10 });
      await this.page.mouse.up();

      await expect
        .poll(() => this.panelLayout(), { message: 'panel layout after the drag' })
        .not.toEqual(before);
      return this.panelLayout();
    });
  }

  /**
   * Steps the row-detail panel to the next/previous dataset item using the
   * panel's own arrow navigation, and waits for the `row` query param to catch
   * up.
   *
   * These are the header's labelled buttons; the `side-panel-next` /
   * `side-panel-previous` testids belong to the default header, which this
   * panel replaces with its own. Scoped to the panel and anchored on the label
   * because "Next" as a loose substring also matches unrelated buttons whose
   * accessible name merely contains it (a dataset called "…-next-…", say).
   */
  async goToNextRow(expectedDatasetItemId: string): Promise<void> {
    await this.stepRow('Next', expectedDatasetItemId);
  }

  async goToPreviousRow(expectedDatasetItemId: string): Promise<void> {
    await this.stepRow('Previous', expectedDatasetItemId);
  }

  private async stepRow(label: 'Next' | 'Previous', expectedDatasetItemId: string): Promise<void> {
    await test.step(`step the panel to the ${label.toLowerCase()} item (${expectedDatasetItemId})`, async () => {
      const button = this.rowPanel.getByRole('button', { name: new RegExp(`^${label}`) });
      await expect(button, `panel ${label} button`).toBeEnabled();
      await button.click();
      await expect
        .poll(() => new URL(this.page.url()).searchParams.get('row'), {
          message: `"row" query param after ${label}`,
        })
        .toBe(expectedDatasetItemId);
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

  /**
   * The row-detail slide-over. `ResizableSidePanel` stamps its `panelId` as the
   * container's testid, so this is the compare panel specifically — scoping to
   * it keeps panel locators off the grid rendered behind it.
   */
  private get rowPanel(): Locator {
    return this.page.getByTestId('compare-experiments');
  }

  /** The resizable panels inside the row-detail panel (dataset + one per experiment). */
  private get panelsInRowPanel(): Locator {
    return this.rowPanel.locator('[data-panel]');
  }

  /** The draggable dividers between those panels. */
  private get panelDividers(): Locator {
    return this.rowPanel.locator('[data-panel-resize-handle-id]');
  }

  /** A compared experiment's section in the row-detail panel, keyed by its h2 name. */
  private panelExperimentSection(experimentName: string): Locator {
    return this.page
      .getByRole('heading', { level: 2, name: experimentName })
      .locator('xpath=ancestor::*[.//table][1]');
  }
}
