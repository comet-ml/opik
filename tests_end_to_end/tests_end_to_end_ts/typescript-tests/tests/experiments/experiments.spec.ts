import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { ExperimentsPage } from '../../page-objects/experiments.page';

test.describe('Experiments CRUD Tests', () => {
  test.describe('Experiment creation and visibility', () => {
    test('should verify experiment visibility in UI and SDK', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.checkExperimentExists(createExperiment.name);

      const experimentSdk = await helperClient.getExperiment(createExperiment.id);
      expect(experimentSdk.name).toBe(createExperiment.name);
    });
  });

  test.describe('Experiment deletion', () => {
    const verifyExperimentDeleted = async (
      page: Page,
      helperClient: any,
      experimentName: string,
      experimentId: string
    ) => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.checkExperimentNotExists(experimentName);

      try {
        await helperClient.getExperiment(experimentId);
        throw new Error('Experiment should not exist');
      } catch (error) {
        expect(String(error)).toContain('404');
      }
    };

    test('should delete experiment via UI', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.deleteExperiment(createExperiment.name);

      await verifyExperimentDeleted(
        page,
        helperClient,
        createExperiment.name,
        createExperiment.id
      );
    });

    test('should delete experiment via SDK', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      await helperClient.deleteExperiment(createExperiment.id);

      await verifyExperimentDeleted(
        page,
        helperClient,
        createExperiment.name,
        createExperiment.id
      );
    });
  });
});
