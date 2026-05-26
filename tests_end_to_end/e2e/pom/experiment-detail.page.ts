import { expect, type Locator, type Page } from '@playwright/test';

export class ExperimentDetailPage {
  constructor(
    private readonly page: Page,
    private readonly experimentId: string,
  ) {}

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
