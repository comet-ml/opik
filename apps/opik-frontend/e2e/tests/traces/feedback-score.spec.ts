import { FeedbackScoreData } from "@e2e/entities";
import { expect, test } from "@e2e/fixtures";
import { CATEGORICAL_FEEDBACK_DEFINITION, TRACE_SCORE } from "@e2e/test-data";

const SPAN_SCORE: FeedbackScoreData = {
  name: "hallucination-span",
  source: "sdk",
  value: 0,
};

test.describe("Feedback scores - Display", () => {
  test("Check in table and sidebar", async ({
    project,
    trace1,
    span,
    tracesPage,
  }) => {
    await span.addScore(SPAN_SCORE);
    await trace1.addScore(TRACE_SCORE as FeedbackScoreData);

    // Trace table column
    await tracesPage.goto(project.id);
    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        `feedback_scores_${TRACE_SCORE.name}`,
      ),
    ).toHaveText(`${TRACE_SCORE.value}`);

    // Trace sidebar
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.selectSidebarTab("Feedback scores");
    await expect(
      tracesPage.sidebarScores.getByText(TRACE_SCORE.name),
    ).toBeVisible();
    await tracesPage.sidePanel.close();

    // LLM Calls column
    await tracesPage.switchToLLMCalls();
    await expect(
      tracesPage.table.getCellLocatorByCellId(
        span.name,
        `feedback_scores_${SPAN_SCORE.name}`,
      ),
    ).toHaveText(`${SPAN_SCORE.value}`);

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
    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        `feedback_scores_${categoricalFeedbackDefinition.name}`,
      ),
    ).toHaveText(
      `${CATEGORICAL_FEEDBACK_DEFINITION.details.categories.second}`,
    );

    await expect(
      tracesPage.table.getCellLocatorByCellId(
        trace1.name,
        `feedback_scores_${numericalFeedbackDefinition.name}`,
      ),
    ).toHaveText("5.5");

    // Clear scores
    await tracesPage.openSidePanel(trace1.name);
    await tracesPage.selectSidebarTab("Feedback scores");
    await tracesPage.clearScore(categoricalFeedbackDefinition.name);
    await tracesPage.clearScore(numericalFeedbackDefinition.name);

    // Check empty scores
    await tracesPage.table.checkIsColumnNotExist(
      `feedback_scores.${categoricalFeedbackDefinition.name}`,
    );
    await tracesPage.table.checkIsColumnNotExist(
      `feedback_scores.${numericalFeedbackDefinition.name}`,
    );
  });
});
