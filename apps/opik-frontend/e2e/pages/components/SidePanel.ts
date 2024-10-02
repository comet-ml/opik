import { Locator, Page } from "@playwright/test";

export class SidePanel {
  readonly page: Page;
  readonly container: Locator;

  constructor(page: Page, panelId: string) {
    this.page = page;
    this.container = page.getByTestId(panelId);
  }

  async close() {
    await this.container.getByTestId("side-panel-close").click();
  }

  async next() {
    await this.container.getByTestId("side-panel-next").click();
  }

  async previous() {
    await this.container.getByTestId("side-panel-previous").click();
  }
}
