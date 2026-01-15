import { Page, Locator } from '@playwright/test';

export class DatasetItemsPage {
  constructor(private page: Page) {}

  private get nextPageButton(): Locator {
    return this.page
      .locator('div')
      .filter({ hasText: /^Showing (\d+)-(\d+) of (\d+)/ })
      .nth(2)
      .locator('button:nth-of-type(3)');
  }

  async removeDefaultColumns() {
    await this.page.getByRole('button', { name: 'Columns' }).click();

    const createdToggle = this.page
      .getByRole('button', { name: 'Created', exact: true })
      .getByRole('checkbox');
    if (await createdToggle.isChecked()) {
      await createdToggle.click();
    }

    const lastUpdatedToggle = this.page
      .getByRole('button', { name: 'Last updated' })
      .getByRole('checkbox');
    if (await lastUpdatedToggle.isChecked()) {
      await lastUpdatedToggle.click();
    }

    const createdByToggle = this.page
      .getByRole('button', { name: 'Created by', exact: true })
      .getByRole('checkbox');
    if (await createdByToggle.isChecked()) {
      await createdByToggle.click();
    }

    await this.page.keyboard.press('Escape');
  }

  async deleteFirstItemAndGetContent(): Promise<Record<string, string>> {
    await this.removeDefaultColumns();
    const headerCells = await this.page.locator('th').allInnerTexts();
    const keys = headerCells.slice(1, -1);
    const item: Record<string, string> = {};

    const row = this.page.locator('tr').nth(1);
    const cells = await row.locator('td').all();

    for (let cellIndex = 0; cellIndex < cells.length - 2; cellIndex++) {
      const cell = cells[cellIndex + 1];
      let content = '';

      if (cellIndex === 0) {
        await cell.getByRole('button').hover();
        await row.getByRole('button').nth(1).click();
        content = await this.page.evaluate(() => (navigator as any).clipboard.readText());
      } else {
        content = (await cell.textContent()) || '';
      }

      item[keys[cellIndex]] = content;
    }

    await row.getByRole('button', { name: 'Actions menu' }).click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete dataset item' }).click();

    return item;
  }

  async insertDatasetItem(item: string) {
    await this.page.getByRole('button', { name: 'Create dataset item' }).click();
    const textbox = this.page.getByRole('textbox');
    await textbox.focus();
    await this.page.keyboard.press('Meta+A');
    await this.page.keyboard.press('Backspace');
    await textbox.fill(item);
    await this.page.getByRole('button', { name: 'Create dataset item' }).click();
  }

  async getAllDatasetItemsOnCurrentPage(): Promise<Array<Record<string, string>>> {
    await this.removeDefaultColumns();
    await this.page.waitForTimeout(500);

    const headerCells = await this.page.locator('th').allInnerTexts();
    const keys = headerCells.slice(1, -1); // Remove first (checkbox) and last (actions)
    const items: Array<Record<string, string>> = [];

    const rows = await this.page.locator('tr').all();

    // Start from index 1 to skip header row
    for (let rowIndex = 1; rowIndex < rows.length; rowIndex++) {
      const row = rows[rowIndex];
      const item: Record<string, string> = {};
      const cells = await row.locator('td').all();

      // Iterate through cells, skipping first (checkbox) and last (actions)
      for (let cellIndex = 0; cellIndex < cells.length - 2; cellIndex++) {
        const cell = cells[cellIndex + 1];
        let content = '';

        // First data column (ID) - copy from clipboard as it may be truncated
        if (cellIndex === 0) {
          await cell.getByRole('button').hover();
          await row.getByRole('button').nth(1).click();
          await this.page.waitForTimeout(50);
          content = await this.page.evaluate(() => (navigator as any).clipboard.readText());
        } else {
          content = (await cell.textContent()) || '';
        }

        item[keys[cellIndex]] = content.trim();
      }

      items.push(item);
    }

    return items;
  }

  async getAllItemsInDataset(): Promise<Array<Record<string, string>>> {
    const items: Array<Record<string, string>> = [];
    items.push(...(await this.getAllDatasetItemsOnCurrentPage()));

    while ((await this.nextPageButton.isVisible()) && (await this.nextPageButton.isEnabled())) {
      await this.nextPageButton.click();
      await this.page.waitForTimeout(500);
      items.push(...(await this.getAllDatasetItemsOnCurrentPage()));
    }

    return items;
  }

  async waitForEmptyDatasetMessage() {
    await this.page.getByText('There are no dataset items yet').waitFor({ state: 'visible' });
  }
}
