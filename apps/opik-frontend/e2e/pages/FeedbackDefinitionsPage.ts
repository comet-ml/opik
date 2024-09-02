import { Locator, Page } from "@playwright/test";

export class FeedbackDefinitionsPage {
  readonly title: Locator;

  constructor(readonly page: Page) {
    this.title = page.getByRole("heading", { name: "Feedback definitions" });
  }

  async goto() {
    await this.page.goto("/default/feedback-definitions");
  }
}
