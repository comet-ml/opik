import { test as base, BaseFixtures } from './base.fixture';
import { FeedbackScoresPage } from '../page-objects/feedback-scores.page';
import { ExperimentsPage } from '../page-objects/experiments.page';
import { PromptsPage, PromptDetailsPage } from '../page-objects/prompts.page';
import type { FeedbackDefinition, Experiment, Prompt } from '../helpers/test-helper-client';

export type FeedbackScoresFixtures = {
  feedbackScoresPage: FeedbackScoresPage;
  createCategoricalFeedback: FeedbackDefinition;
  createNumericalFeedback: FeedbackDefinition;
};

export type ExperimentsFixtures = {
  experimentsPage: ExperimentsPage;
  createExperiment: Experiment;
};

export type PromptsFixtures = {
  promptsPage: PromptsPage;
  promptDetailsPage: PromptDetailsPage;
  createPrompt: Prompt;
};

export const test = base.extend<
  BaseFixtures & FeedbackScoresFixtures & ExperimentsFixtures & PromptsFixtures
>({
  // Feedback Scores Page Object
  feedbackScoresPage: async ({ page }, use) => {
    const feedbackScoresPage = new FeedbackScoresPage(page);
    await use(feedbackScoresPage);
  },

  // Create Categorical Feedback Definition
  createCategoricalFeedback: async ({ helperClient }, use) => {
    const name = `test-categorical-${Date.now()}`;
    const feedback = await helperClient.createFeedbackDefinition(name, 'categorical', {
      categories: { a: 1, b: 2 },
    });

    await use(feedback);

    try {
      await helperClient.deleteFeedbackDefinition(feedback.id);
    } catch (error) {
      console.warn(`Failed to cleanup feedback definition ${name}:`, error);
    }
  },

  // Create Numerical Feedback Definition
  createNumericalFeedback: async ({ helperClient }, use) => {
    const name = `test-numerical-${Date.now()}`;
    const feedback = await helperClient.createFeedbackDefinition(name, 'numerical', {
      min: 0,
      max: 1,
    });

    await use(feedback);

    try {
      await helperClient.deleteFeedbackDefinition(feedback.id);
    } catch (error) {
      console.warn(`Failed to cleanup feedback definition ${name}:`, error);
    }
  },

  // Experiments Page Object
  experimentsPage: async ({ page }, use) => {
    const experimentsPage = new ExperimentsPage(page);
    await use(experimentsPage);
  },

  // Create Experiment
  createExperiment: async ({ helperClient, datasetName }, use) => {
    await helperClient.createDataset(datasetName);
    await helperClient.waitForDatasetVisible(datasetName, 10);

    const experimentName = `test-experiment-${Date.now()}`;
    const experiment = await helperClient.createExperiment(experimentName, datasetName);

    await use(experiment);

    try {
      await helperClient.deleteExperiment(experiment.id);
      await helperClient.deleteDataset(datasetName);
    } catch (error) {
      console.warn(`Failed to cleanup experiment ${experimentName}:`, error);
    }
  },

  // Prompts Page Object
  promptsPage: async ({ page }, use) => {
    const promptsPage = new PromptsPage(page);
    await use(promptsPage);
  },

  // Prompt Details Page Object
  promptDetailsPage: async ({ page }, use) => {
    const promptDetailsPage = new PromptDetailsPage(page);
    await use(promptDetailsPage);
  },

  // Create Prompt
  createPrompt: async ({ helperClient }, use) => {
    const name = `test-prompt-${Date.now()}`;
    const promptText = 'This is a test prompt';
    const prompt = await helperClient.createPrompt(name, promptText);

    await use(prompt);

    try {
      await helperClient.deletePrompt(prompt.name);
    } catch (error) {
      console.warn(`Failed to cleanup prompt ${name}:`, error);
    }
  },
});

export { expect } from '@playwright/test';
