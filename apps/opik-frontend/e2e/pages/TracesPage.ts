import { Locator, Page } from "@playwright/test";

export class TracesPage {
  readonly llmCalls: Locator;
  readonly sidebarCloseButton: Locator;
  readonly sidebarScores: Locator;
  readonly tableScores: Locator;
  readonly title: Locator;

  constructor(readonly page: Page) {
    this.llmCalls = page.getByText("LLM calls");
    this.tableScores = page.getByTestId("feedback-score-tag");
    this.sidebarCloseButton = page.getByTestId("side-panel-close");
    this.sidebarScores = page.getByLabel("Feedback Scores");
    this.title = page.getByRole("heading", { name: "Traces" });
  }

  async goto(projectId: string) {
    await this.page.goto(`/default/projects/${projectId}/traces`);
  }

  async clearScore(name: string) {
    await this.page
      .getByRole("row", { name: `ui ${name}` })
      .getByRole("button")
      .click();
    await this.page
      .getByRole("button", { name: "Clear feedback score" })
      .click();
  }

  async closeSidebar() {
    await this.sidebarCloseButton.click();
  }

  getRow(name: string) {
    return this.page
      .locator("tr")
      .filter({
        has: this.page.locator("td").getByText(name),
      })
      .first();
  }

  getScoreValueCell(name: string) {
    return this.page.locator(`[data-test-value="${name}"]`).first();
  }

  getScoreValue(name: string) {
    return this.tableScores
      .filter({
        has: this.page.getByTestId("feedback-score-tag-label").getByText(name),
      })
      .first()
      .getByTestId("feedback-score-tag-value");
  }

  async openSidebar(name: string) {
    await this.getRow(name).click();
  }

  async openAnnotate() {
    await this.page.getByRole("button", { name: "Annotate" }).click();
  }

  async closeAnnotate() {
    await this.page.getByRole("button", { name: "Close" }).click();
  }

  async selectSidebarTab(name: string) {
    await this.page.getByRole("tab", { name }).click();
  }

  async setCategoricalScore(name: string, categoryName: string) {
    await this.getScoreValueCell(name)
      .getByRole("radio", { name: categoryName })
      .click();
  }

  async setNumericalScore(name: string, value: number) {
    await this.getScoreValueCell(name).locator("input").fill(String(value));
  }

  async switchToLLMCalls() {
    await this.llmCalls.click();
  }
}
