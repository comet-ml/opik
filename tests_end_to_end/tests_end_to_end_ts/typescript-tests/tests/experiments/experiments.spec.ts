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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that experiments created via the SDK are properly visible and accessible in both the UI and SDK interfaces.

Steps:
1. Create an experiment via SDK (handled by fixture)
2. Navigate to the experiments page in the UI
3. Verify the experiment appears in the UI list
4. Retrieve the experiment via SDK
5. Verify the experiment name matches expected value

This test ensures proper synchronization between UI and backend after SDK-based experiment creation.`
      });

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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that experiments can be deleted through the UI, and the deletion is properly reflected in the SDK.

Steps:
1. Create an experiment via SDK (handled by fixture)
2. Navigate to the experiments page
3. Delete the experiment using the UI delete action
4. Verify the experiment no longer appears in the UI
5. Verify the experiment returns 404 when fetched via SDK

This test ensures UI deletions propagate correctly to the backend and SDK.`
      });

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
      test.info().annotations.push({
        type: 'description',
        description: `Tests that experiments can be deleted through the SDK, and the deletion is properly reflected in the UI.

Steps:
1. Create an experiment via SDK (handled by fixture)
2. Delete the experiment using SDK
3. Verify the experiment no longer appears in the UI
4. Verify the experiment returns 404 when fetched via SDK

This test ensures SDK deletions are reflected in the UI.`
      });

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
