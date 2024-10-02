import { FeedbackScoreData } from "@e2e/entities";
import { expect, test } from "@e2e/fixtures";
import { CATEGORICAL_FEEDBACK_DEFINITION } from "@e2e/test-data";

const SPAN_SCORE: FeedbackScoreData = {
  name: "hallucination-span",
  source: "sdk",
  value: 0,
};

const TRACE_SCORE: FeedbackScoreData = {
  name: "hallucination-trace",
  source: "sdk",
  value: 1,
};

test.describe("Feedback scores - Display", () => {
  test("Check in table and sidebar", async ({
    page,
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await span.addScore(SPAN_SCORE);
    await trace1.addScore(TRACE_SCORE);

    // Trace table column
    await tracesPage.goto(project.id);
    await expect(page.locator("td").getByText(TRACE_SCORE.name)).toBeVisible();

    // Trace sidebar
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.selectSidebarTab("Feedback scores");
    await expect(
      tracesPage.sidebarScores.getByText(TRACE_SCORE.name),
    ).toBeVisible();
    await tracesPage.sidePanel.close();

    // LLM Calls column
    await tracesPage.switchToLLMCalls();
    await expect(page.locator("td").getByText(SPAN_SCORE.name)).toBeVisible();

    // LLM Calls sidebar
    await tracesPage.openSidePanel(span.name);
    await tracesPage.selectSidebarTab("Feedback scores");
    await expect(
      tracesPage.sidebarScores.getByText(SPAN_SCORE.name),
    ).toBeVisible();
  });

  test("Set and clear a feedback score in a trace", async ({
    categoricalFeedbackDefinition,
    numericalFeedbackDefinition,
    project,
    trace1,
    tracesPage,
  }) => {
    await tracesPage.goto(project.id);

    await expect(
      tracesPage.table.getRowLocatorByCellText(trace1.name),
    ).toBeVisible();
    await expect(tracesPage.tableScores).toHaveCount(0);

    // Set scores
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.openAnnotate();
    await tracesPage.setCategoricalScore(
      categoricalFeedbackDefinition.name,
      "second",
    );
    await tracesPage.setNumericalScore(numericalFeedbackDefinition.name, 5.5);
    await tracesPage.closeAnnotate();
    await tracesPage.sidePanel.close();

    // Check scores in the table
    await expect(tracesPage.tableScores).toHaveCount(2);
    await expect(
      tracesPage.getScoreValue(categoricalFeedbackDefinition.name),
    ).toHaveText(
      String(CATEGORICAL_FEEDBACK_DEFINITION.details.categories.second),
    );
    await expect(
      tracesPage.getScoreValue(numericalFeedbackDefinition.name),
    ).toHaveText("5.5");

    // Clear scores
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.selectSidebarTab("Feedback scores");
    await tracesPage.clearScore(categoricalFeedbackDefinition.name);
    await tracesPage.clearScore(numericalFeedbackDefinition.name);

    // Check empty scores
    await expect(tracesPage.tableScores).toHaveCount(0);
  });
});
