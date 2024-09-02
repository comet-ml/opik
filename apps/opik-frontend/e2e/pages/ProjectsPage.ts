import { Locator, Page } from "@playwright/test";

export class ProjectsPage {
  readonly title: Locator;

  constructor(readonly page: Page) {
    this.title = page.getByRole("heading", { name: "Projects" });
  }

  async goto() {
    await this.page.goto("/default/projects");
  }

  async goToProject(name: string) {
    const cell = await this.page.locator("td").getByText(name);

    await cell.click();
  }
}
