import { expect, Locator, Page } from "@playwright/test";

export class Table {
  readonly page: Page;
  readonly tBody: Locator;

  constructor(page: Page) {
    this.page = page;
    this.tBody = page.locator("tbody");
  }

  getRowLocatorByCellText(value: string) {
    return this.tBody.locator("tr").filter({
      has: this.page.locator("td").getByText(value, { exact: true }),
    });
  }

  async hasRowCount(count: number) {
    await expect(this.tBody.locator("tr")).toHaveCount(count);
  }

  async hasNoData() {
    await expect(this.tBody.getByTestId("no-data-row")).toBeVisible();
  }

  async openRowActionsByCellText(value: string) {
    await this.getRowLocatorByCellText(value).getByRole("button").click();
  }
}
