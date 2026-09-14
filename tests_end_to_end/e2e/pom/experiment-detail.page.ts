import { expect, test, type Locator, type Page } from '@playwright/test';

export class ExperimentDetailPage {
  constructor(
    private readonly page: Page,
    private readonly experimentId: string,
  ) {}

  /** The "Logs" tab trigger, which replaced the old "Go to logs" tag (OPIK-6739). */
  get logsTab(): Locator {
    return this.page.getByRole('tab', { name: 'Logs' });
  }

  /** The removed "Go to logs" tag — kept as a locator so tests can assert it is gone. */
  get goToLogsTag(): Locator {
    return this.page.getByText('Go to logs');
  }

  /** Open the Logs tab and wait for the URL to reflect it. */
  async openLogsTab(): Promise<void> {
    return test.step('open the Logs tab', async () => {
      await this.logsTab.click();
      await this.page.waitForURL((url) => url.searchParams.get('tab') === 'logs');
    });
  }

  /** Trace rows inside the Logs tab, scoped to the tab so they can't match the items table. */
  get logsTraceRows(): Locator {
    return this.page
      .getByRole('tabpanel')
      .locator('tbody tr[data-row-id]');
  }

  /**
   * Poll until the Logs tab settles on exactly `expected` trace rows.
   *
   * An exact count, not a lower bound: the experiment scope is the point of the tab, so a run of
   * N items must show N traces. More would mean the scope leaked and the whole project is listed.
   */
  async waitForLogsTraceRows(expected: number, timeoutMs = 30_000): Promise<void> {
    return test.step(`wait for ${expected} trace rows in the Logs tab`, async () => {
      await expect(this.logsTraceRows).toHaveCount(expected, {
        timeout: timeoutMs,
      });
    });
  }

  async waitForReady(): Promise<void> {
    // The page renders the experiment name as the h1.
    const h1 = this.page.getByRole('heading', { level: 1 });
    await h1.waitFor({ state: 'visible' });
    // Items table is inside the "Experiment items" tabpanel; wait for at least one row.
    await this.itemRows.first().waitFor({ state: 'visible' });
  }

  async countItems(): Promise<number> {
    return this.itemRows.count();
  }

  async readItemScore(datasetItemId: string, metricName: string): Promise<number> {
    const cell = this.scoreCell(datasetItemId, metricName);
    await expect(cell, `score cell for dataset item ${datasetItemId} / metric ${metricName}`)
      .toBeVisible();
    const text = ((await cell.textContent()) ?? '').trim();
    const value = parseFloat(text);
    if (Number.isNaN(value)) {
      throw new Error(
        `ExperimentDetailPage.readItemScore: could not parse "${text}" as a number for item ${datasetItemId} metric ${metricName}`,
      );
    }
    return value;
  }

  async readAggregateScore(): Promise<number> {
    const valueEl = this.page.getByTestId('feedback-score-tag-value').first();
    await expect(valueEl, 'aggregate score chip value').toBeVisible();
    const text = ((await valueEl.textContent()) ?? '').trim();
    const value = parseFloat(text);
    if (Number.isNaN(value)) {
      throw new Error(`ExperimentDetailPage.readAggregateScore: could not parse "${text}" as a number`);
    }
    return value;
  }

  /**
   * The items table's pagination footer — "Showing 1-100 of 1,200", parsed.
   *
   * `total` is the assertion that matters on a large experiment, and it is the
   * only one available: the items table is virtualised, so roughly a third of a
   * page's rows are in the DOM at any moment and `itemRows.count()` is a
   * property of the scroll position, not of the experiment. The footer reads
   * `total` from the listing's own envelope instead, so an upload that lost
   * items shows up here.
   *
   * Rendered with `toLocaleString()`, hence the comma strip. Scoped to exactly
   * one match so a second table's footer (the Logs tab's, on another tabpanel)
   * cannot answer for this one.
   */
  async readPaginationSummary(): Promise<{ from: number; to: number; total: number }> {
    return test.step('read the items table pagination summary', async () => {
      const summary = this.paginationSummary;
      await expect(summary, 'exactly one items-table pagination footer').toHaveCount(1);
      const text = ((await summary.textContent()) ?? '').trim();
      const match = /^Showing ([\d,]+)-([\d,]+) of ([\d,]+)$/.exec(text);
      if (!match) {
        throw new Error(
          `ExperimentDetailPage.readPaginationSummary: could not parse "${text}"`,
        );
      }
      const toNumber = (value: string) => Number(value.replace(/,/g, ''));
      return { from: toNumber(match[1]), to: toNumber(match[2]), total: toNumber(match[3]) };
    });
  }

  /**
   * Jump to the last page of the items table and wait for it to actually render.
   *
   * Both conditions are needed. The footer is derived from the page counter, so
   * it flips the instant the click lands while the table keeps rendering the
   * previous page (`isPlaceholderData`); waiting on the footer alone reads the
   * page you just left. The first row's id is the discriminator — two pages of a
   * uniform table look alike, but no row id appears on both. Same gate, and for
   * the same reason, as `LogsPage.goToNextPage`.
   *
   * No-op when there is only one page: `disabledNext` is set, the click would
   * hit a disabled button, and `to === total` already holds.
   */
  async goToLastPage(): Promise<void> {
    return test.step('jump to the last page of the items table', async () => {
      const before = await this.readPaginationSummary();
      if (before.to === before.total) return;
      const firstRowIdBefore = await this.firstRenderedRowId();

      const button = this.lastPageButton;
      await expect(button, 'exactly one last-page control').toHaveCount(1);
      await button.click();

      await expect
        .poll(
          async () => {
            const summary = await this.readPaginationSummary();
            const firstRowIdNow = await this.firstRenderedRowId();
            return summary.to === summary.total && firstRowIdNow !== firstRowIdBefore;
          },
          { timeout: 30_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(true);
    });
  }

  /**
   * The rows currently in the DOM, each with the feedback score it renders.
   *
   * A window onto the page, not the page: the items table is virtualised, so
   * this returns the rows around the current scroll position. Callers assert on
   * what is in the window, never on a count.
   *
   * Row id and score are read in **one** pass for that reason. Collecting the
   * ids and then looking each one's cell up by id is two reads of a list that
   * moves between them: a row present when its id was collected can be gone by
   * the time its cell is queried, which surfaces as a 15s timeout on a cell that
   * was there a moment ago. One `evaluateAll` cannot disagree with itself.
   *
   * A row whose score cell is not rendered comes back with `score: null` rather
   * than being dropped, so a caller can tell "this row showed no score" from
   * "this row was not on screen" — and can hold a floor on how many scores it
   * expected to see instead of silently asserting over an empty list.
   */
  async readRenderedRowScores(
    metricName: string,
  ): Promise<Array<{ rowId: string; score: number | null }>> {
    return test.step(`read the rendered item rows and their ${metricName} scores`, async () => {
      const rows = await this.itemRows.evaluateAll(
        (elements, metric) =>
          elements.map((row) => {
            const rowId = row.getAttribute('data-row-id') ?? '';
            const cell = row.querySelector(
              `td[data-cell-id="${rowId}_feedback_scores_${metric}"]`,
            );
            const text = (cell?.textContent ?? '').trim();
            const score = Number.parseFloat(text);
            return { rowId, score: Number.isNaN(score) ? null : score };
          }),
        metricName,
      );
      if (rows.some((row) => row.rowId === '')) {
        throw new Error('ExperimentDetailPage.readRenderedRowScores: a row carried no data-row-id');
      }
      return rows;
    });
  }

  /**
   * The topmost rendered row's id, or null while none is rendered.
   *
   * Read through `evaluateAll` rather than `itemRows.first().getAttribute()`:
   * the latter auto-waits and throws when the row it resolved detaches, and this
   * is a *wait condition* on a virtualised table mid-refetch, where detaching is
   * the normal case and must retry rather than fail the test.
   */
  private async firstRenderedRowId(): Promise<string | null> {
    const ids = await this.itemRows.evaluateAll((rows) =>
      rows.map((r) => r.getAttribute('data-row-id') ?? ''),
    );
    return ids[0] ?? null;
  }

  private get paginationSummary(): Locator {
    return this.page.getByText(/^Showing [\d,]+-[\d,]+ of [\d,]+$/);
  }

  /**
   * The pagination control's "jump to last page" button.
   *
   * `DataTablePagination`'s four nav buttons are icon-only — no text, no
   * accessible name, no `data-testid`, identical class lists — so the lucide
   * icon class is the only thing that tells them apart, and the locator is
   * scoped to the element holding the "Showing …" label so an icon elsewhere on
   * the page cannot match. **A `data-testid` belongs on these buttons**; it is
   * not added here for the reason given on `LogsPage.nextPageButton` — these
   * specs are verified against a deployed environment, which a front-end change
   * in the same PR would not reach.
   */
  private get lastPageButton(): Locator {
    return this.paginationSummary
      .locator('xpath=..')
      .locator('button:has(svg.lucide-chevron-last)');
  }

  private scoreCell(datasetItemId: string, metricName: string): Locator {
    return this.page.locator(
      `td[data-cell-id="${datasetItemId}_feedback_scores_${metricName}"]`,
    );
  }

  get itemRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }
}
