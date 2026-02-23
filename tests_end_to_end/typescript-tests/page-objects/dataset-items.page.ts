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
    await this.page.getByTestId('columns-button').click();

    // Wait for the columns menu to be visible
    await this.page.getByRole('menu').waitFor({ state: 'visible' });

    // Use a helper to toggle columns off - re-query each time to avoid stale elements
    const toggleColumnOff = async (columnName: string, exact: boolean = false) => {
      const checkbox = this.page
        .getByRole('menu')
        .getByRole('button', { name: columnName, exact })
        .getByRole('checkbox');
      if (await checkbox.isChecked()) {
        await checkbox.click();
        await this.page.waitForTimeout(100);
      }
    };

    await toggleColumnOff('Created', true);
    await toggleColumnOff('Last updated', false);
    await toggleColumnOff('Created by', true);

    await this.page.keyboard.press('Escape');
    await this.page.getByRole('menu').waitFor({ state: 'hidden' });
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
      const content = (await cell.textContent()) || '';
      item[keys[cellIndex]] = content.trim();
    }

    await row.getByRole('button', { name: 'Actions menu' }).click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Save changes' }).click();
    await this.page.getByRole('button', { name: 'Save changes' }).click();

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
        const content = (await cell.textContent()) || '';
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
