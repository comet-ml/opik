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
    await test.step(`sort the grid by "${metricName}" descending`, async () => {
      const url = new URL(this.page.url());
      url.searchParams.set(
        'sorting',
        JSON.stringify([{ id: `feedback_scores_${metricName}`, desc: true }]),
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

  /** A compared experiment's section in the row-detail panel, keyed by its h2 name. */
  private panelExperimentSection(experimentName: string): Locator {
    return this.page
      .getByRole('heading', { level: 2, name: experimentName })
      .locator('xpath=ancestor::*[.//table][1]');
  }
}
