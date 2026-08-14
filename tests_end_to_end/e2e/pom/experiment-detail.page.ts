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
      await expect
        .poll(async () => this.logsTraceRows.count(), {
          timeout: timeoutMs,
          intervals: [500, 1000, 2000],
        })
        .toBe(expected);
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

  private scoreCell(datasetItemId: string, metricName: string): Locator {
    return this.page.locator(
      `td[data-cell-id="${datasetItemId}_feedback_scores_${metricName}"]`,
    );
  }

  get itemRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }
}
