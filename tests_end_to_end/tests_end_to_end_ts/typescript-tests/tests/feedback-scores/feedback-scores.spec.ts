import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { FeedbackScoresPage } from '../../page-objects/feedback-scores.page';

test.describe('Feedback Scores CRUD Tests', () => {
  test.describe('Feedback definition creation and visibility', () => {
    test('Categorical and numerical feedback score definitions are visible in UI after creation @sanity @happypaths @fullregression @feedbackscores', async ({
      page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that categorical and numerical feedback score definitions are properly visible in the UI after creation.

Steps:
1. Create two feedback definitions via fixtures (categorical and numerical)
2. Navigate to the feedback scores page
3. Verify the categorical feedback definition appears in the table
4. Verify the numerical feedback definition appears in the table

This test ensures feedback definitions are properly created and visible in the UI.`
      });

      await test.step('Navigate to feedback scores page', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.goto();
      });

      await test.step('Verify categorical feedback definition exists', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.checkFeedbackExists(createCategoricalFeedback.name);
      });

      await test.step('Verify numerical feedback definition exists', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.checkFeedbackExists(createNumericalFeedback.name);
      });
    });
  });

  test.describe('Feedback definition editing', () => {
    test('Categorical and numerical feedback definitions can be edited with updated name and values @fullregression @feedbackscores', async ({
    page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that categorical and numerical feedback definitions can be edited with updated names and values.

Steps:
1. Create two feedback definitions via fixtures (categorical and numerical)
2. Navigate to the feedback scores page
3. Edit the categorical feedback definition name and values
4. Edit the numerical feedback definition name and range
5. Verify both definitions exist with new names
6. Verify their types remain correct (categorical/numerical)
7. Verify categorical definition has updated category values
8. Verify numerical definition has updated min/max range

This test ensures feedback definitions can be properly edited and changes are reflected in the UI.`
      });

      const catNewName = `${createCategoricalFeedback.name}_edited`;
      const numNewName = `${createNumericalFeedback.name}_edited`;

      await test.step('Navigate to feedback scores page', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.goto();
      });

      await test.step('Edit categorical feedback definition', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.editFeedbackDefinition(
          createCategoricalFeedback.name,
          catNewName,
          { categories: { test1: 1, test2: 2 } }
        );
      });

      await test.step('Edit numerical feedback definition', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.editFeedbackDefinition(
          createNumericalFeedback.name,
          numNewName,
          { min: 5, max: 10 }
        );
      });

      await test.step('Verify both edited feedback definitions exist with new names', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.checkFeedbackExists(catNewName);
        await feedbackScoresPage.checkFeedbackExists(numNewName);
      });

      await test.step('Verify feedback types are preserved', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        const catType = await feedbackScoresPage.getFeedbackType(catNewName);
        const numType = await feedbackScoresPage.getFeedbackType(numNewName);

        expect(catType).toBe('Categorical');
        expect(numType).toBe('Numerical');
      });

      await test.step('Verify categorical feedback has updated values', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        const catValues = await feedbackScoresPage.getFeedbackValues(catNewName);
        expect(catValues).toContain('test1');
        expect(catValues).toContain('test2');
      });

      await test.step('Verify numerical feedback has updated range', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        const numValues = await feedbackScoresPage.getFeedbackValues(numNewName);
        expect(numValues).toContain('Min: 5');
        expect(numValues).toContain('Max: 10');
      });
    });
  });

  test.describe('Feedback definition deletion', () => {
    test('Categorical and numerical feedback definitions can be deleted via UI @fullregression @feedbackscores', async ({
      page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that categorical and numerical feedback definitions can be deleted via the UI.

Steps:
1. Create two feedback definitions via fixtures (categorical and numerical)
2. Navigate to the feedback scores page
3. Delete the categorical feedback definition
4. Verify it no longer appears in the table
5. Delete the numerical feedback definition
6. Verify it no longer appears in the table

This test ensures feedback definitions can be properly deleted via the UI.`
      });

      await test.step('Navigate to feedback scores page', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.goto();
      });

      await test.step('Delete categorical feedback definition', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.deleteFeedbackDefinition(createCategoricalFeedback.name);
        await feedbackScoresPage.checkFeedbackNotExists(createCategoricalFeedback.name);
      });

      await test.step('Delete numerical feedback definition', async () => {
        const feedbackScoresPage = new FeedbackScoresPage(page);
        await feedbackScoresPage.deleteFeedbackDefinition(createNumericalFeedback.name);
        await feedbackScoresPage.checkFeedbackNotExists(createNumericalFeedback.name);
      });
    });
  });
});
