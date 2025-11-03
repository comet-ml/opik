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
