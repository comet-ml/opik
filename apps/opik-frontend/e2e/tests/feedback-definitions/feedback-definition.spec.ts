import { expect, test } from "@e2e/fixtures";
import {
  CATEGORICAL_FEEDBACK_DEFINITION,
  CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED,
  NUMERICAL_FEEDBACK_DEFINITION,
  NUMERICAL_FEEDBACK_DEFINITION_MODIFIED,
} from "@e2e/test-data";

test.describe("Feedback definitions page", () => {
  test("Check search", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsPage,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsPage.goto();

    await expect(feedbackDefinitionsPage.title).toBeVisible();

    // search and validate feedback definitions
    await feedbackDefinitionsPage.table.hasRowCount(2);

    await feedbackDefinitionsPage.search.search(
      categoricalFeedbackDefinition.name,
    );
    await feedbackDefinitionsPage.table.hasRowCount(1);

    await feedbackDefinitionsPage.search.search(
      numericalFeedbackDefinition.name,
    );
    await feedbackDefinitionsPage.table.hasRowCount(1);

    await feedbackDefinitionsPage.search.search("invalid_search_string");
    await feedbackDefinitionsPage.table.hasNoData();
  });

  test("Check adding/deleting of items", async ({
    feedbackDefinitionsPage,
  }) => {
    await feedbackDefinitionsPage.goto();

    // create and validate categorical feedback definition
    await feedbackDefinitionsPage.addFeedbackDefinition(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsPage.checkIsExistOnTable(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );

    // create and validate numerical feedback definition
    await feedbackDefinitionsPage.addFeedbackDefinition(
      NUMERICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsPage.checkIsExistOnTable(
      NUMERICAL_FEEDBACK_DEFINITION,
    );

    // delete and validate feedback definitions
    await feedbackDefinitionsPage.deleteFeedbackDefinition(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsPage.checkIsNotExistOnTable(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );

    await feedbackDefinitionsPage.deleteFeedbackDefinition(
      NUMERICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsPage.checkIsNotExistOnTable(
      NUMERICAL_FEEDBACK_DEFINITION,
    );
  });

  test("Check editing of items", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsPage,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsPage.goto();

    // modify and validate numeric to categorical feedback definition
    await feedbackDefinitionsPage.editFeedbackDefinition(
      numericalFeedbackDefinition.name,
      CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED,
    );

    await feedbackDefinitionsPage.checkIsExistOnTable(
      CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED,
    );

    // modify and validate categorical to numeric feedback definition
    await feedbackDefinitionsPage.editFeedbackDefinition(
      categoricalFeedbackDefinition.name,
      NUMERICAL_FEEDBACK_DEFINITION_MODIFIED,
    );

    await feedbackDefinitionsPage.checkIsExistOnTable(
      NUMERICAL_FEEDBACK_DEFINITION_MODIFIED,
    );
  });

  test("Check values column", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsPage,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsPage.goto();

    await feedbackDefinitionsPage.columns.selectAll();

    await feedbackDefinitionsPage.checkNumericValueColumn(
      numericalFeedbackDefinition,
    );
    await feedbackDefinitionsPage.checkCategoricalValueColumn(
      categoricalFeedbackDefinition,
    );
  });
});
