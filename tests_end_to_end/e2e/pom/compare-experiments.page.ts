import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/** Slot marker for a virtualization spacer cell — a rendered gap, not a column. */
export const COLUMN_SPACER = '__spacer__';

/** One rendered body cell of the Results grid. */
export interface ResultsGridCell {
  rowId: string;
  columnId: string;
  /** The whole cell's text — what an unsplit column (a dataset field) shows. */
  text: string;
  /**
   * Per-experiment band text, indexed by the experiment's position in the
   * `experiments` query array. Sparse where a band did not render.
   */
  bands: string[];
}

/**
 * Everything the Results grid has in the DOM at one horizontal scroll offset.
 *
 * Column virtualization means "in the DOM" is a moving subset of the grid, so a
 * scan is taken per offset and the assertions are about what each scan contains
 * and how its parts line up.
 */
export interface ResultsGridScan {
  scrollLeft: number;
  scrollWidth: number;
  clientWidth: number;
  /** Section title per column slot; `''` where the column sits under no section. */
  groupSlots: string[];
  /** Leaf column id per column slot, in the same slot order as `groupSlots`. */
  columnSlots: string[];
  /** Slot counts that must agree: the colgroup, every header row, every body row. */
  slotCounts: { colgroup: number; headerRows: number[]; bodyRows: number[] };
  cells: ResultsGridCell[];
  /** True once the rendered window spans the scroller's whole visible width. */
  windowCoversViewport: boolean;
}

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

  /**
   * Read the whole rendered window of the Results grid in one round trip.
   *
   * Deliberately one `evaluate` rather than a locator per cell: the grid this
   * exists for is ~40 columns wide and is scanned at a dozen scroll offsets, so
   * per-cell locators would be thousands of round trips — and, worse, the DOM
   * could re-window between two of them, which would make a mismatch a race
   * rather than a finding. A single snapshot is internally consistent.
   *
   * Structural attributes (`data-row-id`, `data-cell-id`, `data-virtual-row-id`,
   * `aria-hidden` on spacers) are what the shared DataTable stamps; there is no
   * testid on this table, and these are the same hooks the rest of this POM
   * already addresses cells by.
   */
  async scanResultsGrid(): Promise<ResultsGridScan> {
    return test.step('scan the rendered Results grid window', async () => {
      return this.readResultsGrid(null);
    });
  }

  /**
   * Scroll the Results grid horizontally and wait for the column window to
   * catch up. `Number.MAX_SAFE_INTEGER` scrolls to the far right edge.
   *
   * The wait is on the window covering the viewport rather than on a timeout:
   * mid-render the DOM still holds the previous window, and a scan taken then
   * would describe a grid nobody is looking at.
   */
  async scrollResultsGridTo(target: number): Promise<ResultsGridScan> {
    return test.step(`scroll the Results grid to ${target}`, async () => {
      let scan = await this.readResultsGrid(target);
      await expect
        .poll(
          async () => {
            scan = await this.readResultsGrid(null);
            return scan.windowCoversViewport;
          },
          { message: `column window covers the viewport at scrollLeft ${target}` },
        )
        .toBe(true);
      return scan;
    });
  }

  /**
   * Wait until a given column is reachable at the grid's far-right edge.
   *
   * The Results grid builds its feedback-score columns from a second request
   * that resolves after the rows do, so "rows are visible" is not "the grid is
   * as wide as it will get". A sweep started before that lands would scan a
   * narrower table and conclude columns had been dropped.
   */
  async waitForRightmostColumn(columnId: string): Promise<void> {
    await test.step(`wait for column "${columnId}" at the grid's right edge`, async () => {
      await expect
        .poll(
          async () => {
            const scan = await this.scrollResultsGridTo(Number.MAX_SAFE_INTEGER);
            return scan.columnSlots.includes(columnId);
          },
          { message: `column "${columnId}" rendered at the far right of the Results grid` },
        )
        .toBe(true);
    });
  }

  /**
   * Optionally scroll, then snapshot. Both halves live in one in-page routine
   * because both need the same scroller — the nearest horizontally scrollable
   * ancestor of the table — and resolving it twice invites the two halves to
   * disagree about which element they are talking about.
   */
  private async readResultsGrid(scrollTo: number | null): Promise<ResultsGridScan> {
    await expect(this.resultsTable, 'exactly one Results grid on the page').toHaveCount(1);
    return this.resultsTable.evaluate(
      (table: HTMLTableElement, { spacerMarker, scrollTo: x }) => {
        const isHorizontallyScrollable = (el: HTMLElement): boolean => {
          const overflowX = window.getComputedStyle(el).overflowX;
          return (
            (overflowX === 'auto' || overflowX === 'scroll') && el.scrollWidth > el.clientWidth
          );
        };

        let scroller: HTMLElement | null = table.parentElement;
        while (scroller && !isHorizontallyScrollable(scroller)) {
          scroller = scroller.parentElement;
        }
        if (!scroller) {
          throw new Error(
            'readResultsGrid: no horizontally scrollable ancestor — the grid does not overflow its viewport',
          );
        }
        if (x !== null) scroller.scrollLeft = x;

        const headerRows = Array.from(table.querySelectorAll('thead tr'));
        const bodyRows = Array.from(
          table.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row-id]'),
        );

        /** Expand a rendered row into one entry per column slot, honouring colSpan. */
        const slotsOf = (cells: Element[], valueOf: (cell: Element) => string): string[] =>
          cells.flatMap((cell) =>
            cell.hasAttribute('aria-hidden')
              ? [spacerMarker]
              : new Array<string>(
                  Math.max((cell as HTMLTableCellElement).colSpan || 1, 1),
                ).fill(valueOf(cell)),
          );

        const groupSlots = slotsOf(Array.from(headerRows[0]?.children ?? []), (cell) =>
          (cell.textContent ?? '').trim(),
        );

        const columnIdOf = (cell: Element, rowId: string): string =>
          (cell.getAttribute('data-cell-id') ?? '').slice(rowId.length + 1);

        const firstBodyRow = bodyRows[0];
        const columnSlots = firstBodyRow
          ? slotsOf(Array.from(firstBodyRow.children), (cell) =>
              columnIdOf(cell, firstBodyRow.getAttribute('data-row-id') ?? ''),
            )
          : [];

        const cells: Array<{
          rowId: string;
          columnId: string;
          text: string;
          bands: string[];
        }> = [];
        for (const row of bodyRows) {
          const rowId = row.getAttribute('data-row-id') ?? '';
          for (const cell of Array.from(row.children)) {
            if (cell.hasAttribute('aria-hidden')) continue;
            const bands: string[] = [];
            for (const band of Array.from(
              cell.querySelectorAll<HTMLElement>('div[data-virtual-row-id]'),
            )) {
              const suffix = (band.getAttribute('data-virtual-row-id') ?? '').slice(
                rowId.length + 1,
              );
              const index = Number(suffix);
              if (Number.isInteger(index)) bands[index] = (band.textContent ?? '').trim();
            }
            cells.push({
              rowId,
              columnId: columnIdOf(cell, rowId),
              text: (cell.textContent ?? '').trim(),
              bands,
            });
          }
        }

        // The window has settled once the columns in the DOM span the scroller's
        // whole visible width: a lagging window leaves the far edge uncovered.
        // Left-pinned columns are sticky and always rendered, so they are
        // measured as a width to skip rather than as coverage.
        const tableRight = table.getBoundingClientRect().right;
        const scrollerRect = scroller.getBoundingClientRect();
        let stickyWidth = 0;
        let minLeft = Number.POSITIVE_INFINITY;
        let maxRight = Number.NEGATIVE_INFINITY;
        for (const cell of Array.from(headerRows[headerRows.length - 1]?.children ?? [])) {
          if (cell.hasAttribute('aria-hidden')) continue;
          const rect = cell.getBoundingClientRect();
          if (window.getComputedStyle(cell).position === 'sticky') {
            stickyWidth += rect.width;
            continue;
          }
          minLeft = Math.min(minLeft, rect.left);
          maxRight = Math.max(maxRight, rect.right);
        }

        return {
          scrollLeft: Math.round(scroller.scrollLeft),
          scrollWidth: Math.round(scroller.scrollWidth),
          clientWidth: Math.round(scroller.clientWidth),
          groupSlots,
          columnSlots,
          slotCounts: {
            colgroup: table.querySelectorAll('colgroup > col').length,
            headerRows: headerRows.map(
              (row) => slotsOf(Array.from(row.children), () => '').length,
            ),
            bodyRows: bodyRows.map(
              (row) => slotsOf(Array.from(row.children), () => '').length,
            ),
          },
          cells,
          windowCoversViewport:
            minLeft <= scrollerRect.left + stickyWidth + 1 &&
            maxRight >= Math.min(scrollerRect.right, tableRight) - 1,
        };
      },
      { spacerMarker: COLUMN_SPACER, scrollTo },
    );
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

  /** The Results grid itself — the one table on the page that has item rows. */
  private get resultsTable(): Locator {
    return this.page.locator('table').filter({ has: this.page.locator('tbody tr[data-row-id]') });
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
