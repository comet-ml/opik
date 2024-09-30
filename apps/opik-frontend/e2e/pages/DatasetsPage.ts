import { Locator, Page } from "@playwright/test";
import { Table } from "@e2e/pages/components/Table";
import { Search } from "@e2e/pages/components/Search";
import { expect } from "@e2e/fixtures";

export class DatasetsPage {
  readonly title: Locator;
  readonly search: Search;
  readonly table: Table;

  constructor(readonly page: Page) {
    this.title = page.getByRole("heading", { name: "Datasets" });
    this.search = new Search(page);
    this.table = new Table(page);
  }

  async goto() {
    await this.page.goto("/default/datasets");
  }

  async goToDataset(name: string) {
    await this.table.getRowLocatorByCellText(name).click();
  }

  async addDataset(name: string, description?: string) {
    await this.page
      .getByRole("button", {
        name: "Create new dataset",
      })
      .click();
    await this.page.getByPlaceholder("Dataset name").fill(name);

    if (description) {
      await this.page.getByPlaceholder("Dataset description").fill(name);
    }

    await this.page.getByRole("button", { name: "Create dataset" }).click();
  }

  async deleteDataset(name: string) {
    await this.table.openRowActionsByCellText(name);
    await this.page.getByRole("menuitem", { name: "Delete" }).click();
    await this.page.getByLabel(`To validation, type "${name}"`).fill(name);
    await this.page.getByRole("button", { name: "Delete" }).click();
  }

  async checkIsExistOnTable(name: string) {
    await expect(this.table.getRowLocatorByCellText(name)).toBeVisible();
  }

  async checkIsNotExistOnTable(name: string) {
    await expect(this.table.getRowLocatorByCellText(name)).not.toBeVisible();
  }
}
