import { Locator, Page } from "@playwright/test";
import { Search } from "@e2e/pages/components/Search";
import { Table } from "@e2e/pages/components/Table";

export class ProjectsPage {
  readonly title: Locator;
  readonly search: Search;
  readonly table: Table;

  constructor(readonly page: Page) {
    this.title = page.getByRole("heading", { name: "Projects" });
    this.search = new Search(page);
    this.table = new Table(page);
  }

  async goto() {
    await this.page.goto("/default/projects");
  }

  async goToProject(name: string) {
    await this.table
      .getRowLocatorByCellText(name)
      .getByRole("link")
      .first()
      .click();
  }

  async addProject(name: string) {
    await this.page
      .getByRole("button", {
        name: "Create new project",
      })
      .click();
    await this.page.getByPlaceholder("Project name").fill(name);

    await this.page.getByRole("button", { name: "Create project" }).click();
  }

  async deleteProject(name: string) {
    await this.table.openRowActionsByCellText(name);
    await this.page.getByRole("menuitem", { name: "Delete" }).click();
    await this.page.getByRole("button", { name: "Delete project" }).click();
  }
}
