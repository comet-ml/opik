import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { FeedbackScoresPage } from '../../page-objects/feedback-scores.page';

test.describe('Feedback Scores CRUD Tests', () => {
  test.describe('Feedback definition creation and visibility', () => {
    test('should verify categorical and numerical feedback definitions @sanity @regression', async ({
      page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      const feedbackScoresPage = new FeedbackScoresPage(page);
      await feedbackScoresPage.goto();

      await feedbackScoresPage.checkFeedbackExists(createCategoricalFeedback.name);
      await feedbackScoresPage.checkFeedbackExists(createNumericalFeedback.name);
    });
  });

  test.describe('Feedback definition editing', () => {
    test('should edit categorical and numerical feedback definitions @regression', async ({
      page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      const feedbackScoresPage = new FeedbackScoresPage(page);
      await feedbackScoresPage.goto();

      const catNewName = `${createCategoricalFeedback.name}_edited`;
      const numNewName = `${createNumericalFeedback.name}_edited`;

      await feedbackScoresPage.editFeedbackDefinition(
        createCategoricalFeedback.name,
        catNewName,
        { categories: { test1: 1, test2: 2 } }
      );

      await feedbackScoresPage.editFeedbackDefinition(
        createNumericalFeedback.name,
        numNewName,
        { min: 5, max: 10 }
      );

      await feedbackScoresPage.checkFeedbackExists(catNewName);
      await feedbackScoresPage.checkFeedbackExists(numNewName);

      const catType = await feedbackScoresPage.getFeedbackType(catNewName);
      const numType = await feedbackScoresPage.getFeedbackType(numNewName);

      expect(catType).toBe('Categorical');
      expect(numType).toBe('Numerical');

      const catValues = await feedbackScoresPage.getFeedbackValues(catNewName);
      expect(catValues).toContain('test1');
      expect(catValues).toContain('test2');

      const numValues = await feedbackScoresPage.getFeedbackValues(numNewName);
      expect(numValues).toContain('Min: 5');
      expect(numValues).toContain('Max: 10');
    });
  });

  test.describe('Feedback definition deletion', () => {
    test('should delete categorical and numerical feedback definitions @regression', async ({
      page,
      createCategoricalFeedback,
      createNumericalFeedback,
    }) => {
      const feedbackScoresPage = new FeedbackScoresPage(page);
      await feedbackScoresPage.goto();

      await feedbackScoresPage.deleteFeedbackDefinition(createCategoricalFeedback.name);
      await feedbackScoresPage.checkFeedbackNotExists(createCategoricalFeedback.name);

      await feedbackScoresPage.deleteFeedbackDefinition(createNumericalFeedback.name);
      await feedbackScoresPage.checkFeedbackNotExists(createNumericalFeedback.name);
    });
  });
});
