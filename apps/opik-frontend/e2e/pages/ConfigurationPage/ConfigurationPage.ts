import { Page } from "@playwright/test";

export class ConfigurationPage {
  constructor(readonly page: Page) {}

  async goto() {
    await this.page.goto(`/default/configuration`);
  }
}
