import { Locator, Page } from "@playwright/test";

export class Columns {
  readonly page: Page;
  readonly button: Locator;

  constructor(page: Page) {
    this.page = page;
    this.button = page.getByRole("button", { name: "Columns" });
  }

  async selectAll() {
    await this.button.click();

    const checkboxes = await this.page
      .getByRole("button")
      .filter({ has: this.page.locator(`[data-state="unchecked"]`) })
      .all();

    for (let i = checkboxes.length - 1; i >= 0; i--) {
      await checkboxes[i].click();
    }

    await this.page.keyboard.down("Escape");
  }

  async select(column: string) {
    await this.button.click();

    const checkboxes = await this.page
      .getByRole("button")
      .filter({
        has: this.page.locator(`[data-state="unchecked"]`),
        hasText: column,
      })
      .all();

    for (let i = checkboxes.length - 1; i >= 0; i--) {
      await checkboxes[i].click();
    }

    await this.page.keyboard.down("Escape");
  }
}
