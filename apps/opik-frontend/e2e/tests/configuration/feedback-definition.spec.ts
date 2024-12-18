import { test } from "@e2e/fixtures";
import {
  CATEGORICAL_FEEDBACK_DEFINITION,
  CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED,
  NUMERICAL_FEEDBACK_DEFINITION,
  NUMERICAL_FEEDBACK_DEFINITION_MODIFIED,
} from "@e2e/test-data";

test.describe("Feedback definitions page", () => {
  test("Check search", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsTab,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsTab.goto();
    await feedbackDefinitionsTab.table.hasRowCount(2);

    await feedbackDefinitionsTab.search.search(
      categoricalFeedbackDefinition.name,
    );
    await feedbackDefinitionsTab.table.hasRowCount(1);

    await feedbackDefinitionsTab.search.search(
      numericalFeedbackDefinition.name,
    );
    await feedbackDefinitionsTab.table.hasRowCount(1);

    await feedbackDefinitionsTab.search.search("invalid_search_string");
    await feedbackDefinitionsTab.table.hasNoData();
  });

  test("Check adding/deleting of items", async ({ feedbackDefinitionsTab }) => {
    await feedbackDefinitionsTab.goto();

    // create and validate categorical feedback definition
    await feedbackDefinitionsTab.addFeedbackDefinition(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsTab.table.checkIsExist(
      CATEGORICAL_FEEDBACK_DEFINITION.name,
    );

    // create and validate numerical feedback definition
    await feedbackDefinitionsTab.addFeedbackDefinition(
      NUMERICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsTab.table.checkIsExist(
      NUMERICAL_FEEDBACK_DEFINITION.name,
    );

    // delete and validate feedback definitions
    await feedbackDefinitionsTab.deleteFeedbackDefinition(
      CATEGORICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsTab.table.checkIsNotExist(
      CATEGORICAL_FEEDBACK_DEFINITION.name,
    );

    await feedbackDefinitionsTab.deleteFeedbackDefinition(
      NUMERICAL_FEEDBACK_DEFINITION,
    );
    await feedbackDefinitionsTab.table.checkIsNotExist(
      NUMERICAL_FEEDBACK_DEFINITION.name,
    );
  });

  test("Check editing of items", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsTab,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsTab.goto();

    // modify and validate numeric to categorical feedback definition
    await feedbackDefinitionsTab.editFeedbackDefinition(
      numericalFeedbackDefinition.name,
      CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED,
    );

    await feedbackDefinitionsTab.table.checkIsExist(
      CATEGORICAL_FEEDBACK_DEFINITION_MODIFIED.name,
    );

    // modify and validate categorical to numeric feedback definition
    await feedbackDefinitionsTab.editFeedbackDefinition(
      categoricalFeedbackDefinition.name,
      NUMERICAL_FEEDBACK_DEFINITION_MODIFIED,
    );

    await feedbackDefinitionsTab.table.checkIsExist(
      NUMERICAL_FEEDBACK_DEFINITION_MODIFIED.name,
    );
  });

  test("Check values column", async ({
    categoricalFeedbackDefinition,
    feedbackDefinitionsTab,
    numericalFeedbackDefinition,
  }) => {
    await feedbackDefinitionsTab.goto();

    await feedbackDefinitionsTab.columns.selectAll();

    await feedbackDefinitionsTab.checkNumericValueColumn(
      numericalFeedbackDefinition,
    );
    await feedbackDefinitionsTab.checkCategoricalValueColumn(
      categoricalFeedbackDefinition,
    );
  });
});
