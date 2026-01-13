import { Page, Locator } from '@playwright/test';

export class ExperimentItemsPage {
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

  async getTotalNumberOfItemsInExperiment(): Promise<number> {
    const paginationText = await this.paginationButton.innerText();
    const match = paginationText.match(/of (\d+)/);
    return match ? parseInt(match[1], 10) : 0;
  }

  async getIdOfNthExperimentItem(n: number): Promise<string> {
    const row = this.page.locator('tr').nth(n + 1);
    const cell = row.locator('td').nth(1);
    await cell.hover();
    await cell.getByRole('button').nth(1).click();
    return await this.page.evaluate(() => (navigator as any).clipboard.readText());
  }

  async getAllItemIdsOnCurrentPage(): Promise<string[]> {
    const ids: string[] = [];
    const rows = await this.page.locator('tr').all();

    for (let rowIndex = 2; rowIndex < rows.length; rowIndex++) {
      const row = rows[rowIndex];
      const cell = row.locator('td').nth(1);
      await cell.hover();
      await cell.getByRole('button').nth(1).click();
      const id = await this.page.evaluate(() => (navigator as any).clipboard.readText());
      ids.push(id);
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
