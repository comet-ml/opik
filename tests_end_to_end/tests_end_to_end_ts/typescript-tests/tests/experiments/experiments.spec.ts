import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import type { Page } from '@playwright/test';
import { ExperimentsPage } from '../../page-objects/experiments.page';

test.describe('Experiments CRUD Tests', () => {
  test.describe('Experiment creation and visibility', () => {
    test('Experiments created via SDK are visible in both UI and SDK @sanity @happypaths @fullregression @experiments', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      await test.step('Verify experiment is visible in UI', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto();
        await experimentsPage.checkExperimentExists(createExperiment.name);
      });

      await test.step('Verify experiment is retrievable via SDK', async () => {
        const experimentSdk = await helperClient.getExperiment(createExperiment.id);
        expect(experimentSdk.name).toBe(createExperiment.name);
      });
    });
  });

  test.describe('Experiment deletion', () => {
    const verifyExperimentDeleted = async (
      page: Page,
      helperClient: any,
      experimentName: string,
      experimentId: string
    ) => {
      await test.step('Verify experiment is not visible in UI', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto();
        await experimentsPage.checkExperimentNotExists(experimentName);
      });

      await test.step('Verify experiment returns 404 when fetched via SDK', async () => {
        try {
          await helperClient.getExperiment(experimentId);
          throw new Error('Experiment should not exist');
        } catch (error) {
          expect(String(error)).toContain('404');
        }
      });
    };

    test('Experiments can be deleted via UI and deletion is reflected in SDK @fullregression @experiments', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      await test.step('Delete experiment using UI', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto();
        await experimentsPage.deleteExperiment(createExperiment.name);
      });

      await verifyExperimentDeleted(
        page,
        helperClient,
        createExperiment.name,
        createExperiment.id
      );
    });

    test('Experiments can be deleted via SDK and deletion is reflected in UI @fullregression @experiments', async ({
      page,
      helperClient,
      createExperiment,
    }) => {
      await test.step('Delete experiment using SDK', async () => {
        await helperClient.deleteExperiment(createExperiment.id);
      });

      await verifyExperimentDeleted(
        page,
        helperClient,
        createExperiment.name,
        createExperiment.id
      );
    });
  });
});
