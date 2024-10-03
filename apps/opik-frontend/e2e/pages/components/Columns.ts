import { Locator, Page } from "@playwright/test";

export class Columns {
  readonly page: Page;
  readonly button: Locator;

  constructor(page: Page) {
    this.page = page;
    this.button = page.getByRole("button", { name: "Columns" });
  }

  async open() {
    await this.button.click();
  }

  async close() {
    await this.page.keyboard.down("Escape");
  }

  async getCheckboxes(
    state: "checked" | "unchecked" = "unchecked",
    column?: string,
  ) {
    return await this.page
      .getByRole("button")
      .filter({
        has: this.page.locator(`[data-state="${state}"]`),
        hasText: column,
      })
      .all();
  }

  async selectAll() {
    await this.open();
    const checkboxes = await this.getCheckboxes();
    for (let i = checkboxes.length - 1; i >= 0; i--) {
      await checkboxes[i].click();
    }
    await this.close();
  }

  async select(column: string) {
    await this.open();
    const checkboxes = await this.getCheckboxes("unchecked", column);
    if (checkboxes[0]) await checkboxes[0].click();
    await this.close();
  }
}
