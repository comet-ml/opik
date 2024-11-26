import { expect, Locator, Page } from "@playwright/test";

export class Table {
  readonly page: Page;
  readonly tBody: Locator;

  constructor(page: Page) {
    this.page = page;
    this.tBody = page.locator("tbody");
  }

  getRowLocatorByCellText(value: string) {
    return this.tBody
      .locator("tr")
      .filter({
        has: this.page.locator("td").getByText(value, { exact: true }),
      })
      .first();
  }

  getCellLocatorByCellId(value: string, id: string) {
    return this.getRowLocatorByCellText(value).locator(
      `td[data-cell-id$="_${id}"]`,
    );
  }

  getHeaderLocatorByHeaderId(id: string) {
    return this.page.locator(`th[data-header-id$="_${id}"]`);
  }

  async hasRowCount(count: number) {
    await expect(this.tBody.locator("tr")).toHaveCount(count);
  }

  async hasNoData() {
    await expect(this.tBody.getByTestId("no-data-row")).toBeVisible();
  }

  async openRowActionsByCellText(value: string) {
    await this.getRowLocatorByCellText(value)
      .getByRole("button", { name: "Actions menu" })
      .click();
  }

  async checkIsExist(name: string) {
    await expect(this.getRowLocatorByCellText(name)).toBeVisible();
  }

  async checkIsNotExist(name: string) {
    await expect(this.getRowLocatorByCellText(name)).not.toBeVisible();
  }

  async checkIsColumnExist(id: string) {
    await expect(this.getHeaderLocatorByHeaderId(id)).not.toBeVisible();
  }

  async checkIsColumnNotExist(id: string) {
    await expect(this.getHeaderLocatorByHeaderId(id)).not.toBeVisible();
  }
}
