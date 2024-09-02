import { expect, test } from "@e2e/fixtures";

test.describe("Feedback definitions", () => {
  test("Check categorical/numerical feedback definition", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsPage,
    numericalFeedbackDefinition,
    page,
  }) => {
    await feedbackDefinitionsPage.goto();

    await expect(feedbackDefinitionsPage.title).toBeVisible();
    await expect(
      page.locator("td").getByText(categoricalFeedbackDefinition.name),
    ).toBeVisible();
    await expect(
      page.locator("td").getByText(numericalFeedbackDefinition.name),
    ).toBeVisible();
  });
});
