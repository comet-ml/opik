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
    await this.table.getRowLocatorByCellText(name).click();
  }

  async addDatasetItem(input: string, output?: string, meta?: string) {
    await this.page
      .getByRole("button", {
        name: "Create dataset item",
      })
      .click();
    await this.page.locator(".cm-editor .cm-line").nth(0).fill(input);

    if (output) {
      await this.page.locator(".cm-editor .cm-line").nth(1).fill(output);
    }

    if (meta) {
      await this.page.locator(".cm-editor .cm-line").nth(2).fill(meta);
    }

    await this.page.getByRole("button", { name: "Create dataset" }).click();
  }

  async deleteDatasetItem(name: string) {
    await this.table.openRowActionsByCellText(name);
    await this.page.getByRole("menuitem", { name: "Delete" }).click();
    await this.page
      .getByRole("button", { name: "Delete dataset item" })
      .click();
  }

  async checkIsExistOnTable(name: string) {
    await expect(this.table.getRowLocatorByCellText(name)).toBeVisible();
  }

  async checkIsNotExistOnTable(name: string) {
    await expect(this.table.getRowLocatorByCellText(name)).not.toBeVisible();
  }
}
