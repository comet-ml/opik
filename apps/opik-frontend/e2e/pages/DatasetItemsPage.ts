import { Page } from "@playwright/test";
import { Table } from "@e2e/pages/components/Table";
import { expect } from "@e2e/fixtures";
import { Columns } from "@e2e/pages/components/Columns";

export class DatasetItemsPage {
  readonly columns: Columns;
  readonly table: Table;

  constructor(readonly page: Page) {
    this.columns = new Columns(page);
    this.table = new Table(page);
  }

  async goto(id: string) {
    await this.page.goto(`/default/datasets/${id}/items`);
  }

  async openSidebar(name: string) {
    await this.table
      .getRowLocatorByCellText(name)
      .getByRole("button")
      .first()
      .click();
  }

  async addDatasetItem(data: string) {
    await this.page
      .getByRole("button", {
        name: "Create dataset item",
      })
      .click();
    await this.page.locator(".cm-editor .cm-content").first().fill(data);

    await this.page.getByRole("button", { name: "Create dataset" }).click();
  }

  async deleteDatasetItem(name: string) {
    await this.table.openRowActionsByCellText(name);
    await this.page.getByRole("menuitem", { name: "Delete" }).click();
    await this.page
      .getByRole("button", { name: "Delete dataset item" })
      .click();
  }
}
