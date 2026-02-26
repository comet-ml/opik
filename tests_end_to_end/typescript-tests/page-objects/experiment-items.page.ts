import { Page, Locator, expect } from '@playwright/test';

export class ExperimentItemsPage {
  private idColumnIndex = -1;

  constructor(private page: Page) {}

  private get nextPageButton(): Locator {
    return this.page
      .locator('div')
      .filter({ hasText: /^Showing (\d+)-(\d+) of (\d+)/ })
      .nth(2)
      .locator('button:nth-of-type(3)');
  }

  private get paginationButton(): Locator {
    return this.page.getByRole('button', { name: 'Showing' });
  }

  // Ensure the "Dataset item ID" column is visible and resolve its index.
  async initialize(): Promise<void> {
    await this.page.getByTestId('columns-button').click({ timeout: 5000 });
    try {
      await expect(
        this.page.getByRole('button', { name: 'Dataset item ID' }).getByRole('checkbox')
      ).toBeChecked({ timeout: 2000 });
    } catch {
      await this.page.getByRole('button', { name: 'Dataset item ID' }).click();
    }
    await this.page.keyboard.press('Escape');
    this.idColumnIndex = -1; // reset so resolveIdColumnIndex re-detects
  }

  private async resolveIdColumnIndex(): Promise<number> {
    if (this.idColumnIndex >= 0) return this.idColumnIndex;
    // The table has two header rows: a group row and the actual column row.
    // We look at the last thead row for the column names.
    const headerRows = this.page.locator('table thead tr');
    const rowCount = await headerRows.count();
    const headers = headerRows.nth(rowCount - 1).locator('th, td');
    const count = await headers.count();
    for (let i = 0; i < count; i++) {
      const text = await headers.nth(i).innerText();
      if (text.match(/^Dataset item ID/)) {
        this.idColumnIndex = i;
        return this.idColumnIndex;
      }
    }
    throw new Error('Dataset item ID column not found. Was initialize() called?');
  }

  async getTotalNumberOfItemsInExperiment(): Promise<number> {
    const paginationText = await this.paginationButton.innerText();
    const match = paginationText.match(/of (\d+)/);
    return match ? parseInt(match[1], 10) : 0;
  }

  async getAllItemIdsOnCurrentPage(): Promise<string[]> {
    const colIdx = await this.resolveIdColumnIndex();
    const ids: string[] = [];
    // Skip the first tbody row which is the column-headers row (second thead row renders inside tbody in some layouts)
    const rows = this.page.locator('table tbody tr');
    const rowCount = await rows.count();
    for (let i = 0; i < rowCount; i++) {
      const cell = rows.nth(i).locator('td').nth(colIdx);
      const id = (await cell.textContent()) || '';
      const trimmed = id.trim();
      if (trimmed) ids.push(trimmed);
    }
    return ids;
  }

  async getAllItemIdsInExperiment(): Promise<string[]> {
    const ids: string[] = [];
    ids.push(...(await this.getAllItemIdsOnCurrentPage()));

    while ((await this.nextPageButton.isVisible()) && (await this.nextPageButton.isEnabled())) {
      await this.nextPageButton.click();
      await this.page.waitForTimeout(500);
      ids.push(...(await this.getAllItemIdsOnCurrentPage()));
    }

    return ids;
  }
}
