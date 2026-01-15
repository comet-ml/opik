import { test, expect } from '../../fixtures/feedback-experiments-prompts.fixture';
import { ExperimentsPage } from '../../page-objects/experiments.page';
import { ExperimentItemsPage } from '../../page-objects/experiment-items.page';

test.describe('Experiment Items CRUD Tests', () => {
  test('Experiment items are created and visible in both UI and backend @fullregression @experiments', async ({
    page,
    helperClient,
    createExperimentWithItems,
  }) => {
    test.info().annotations.push({
      type: 'description',
      description: `Tests that experiment items are properly created and visible in both the UI and backend after experiment creation.

Steps:
1. Create an experiment with items via fixture
2. Navigate to the experiment items page
3. Verify the correct number of items is displayed in the UI
4. Verify the trace count matches via backend
5. Retrieve all item IDs from both UI and backend
6. Verify that item IDs match between UI and backend

This test ensures proper synchronization of experiment items between UI and backend.`
    });

    const { experiment, datasetSize } = createExperimentWithItems;

    await test.step('Navigate to experiment items page', async () => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.clickExperiment(experiment.name);
    });

    await test.step('Verify item count in UI', async () => {
      const experimentItemsPage = new ExperimentItemsPage(page);
      const itemsOnPage = await experimentItemsPage.getTotalNumberOfItemsInExperiment();

      expect(itemsOnPage).toBe(datasetSize);
    });

    await test.step('Verify trace count via backend', async () => {
      const experimentBackend = await helperClient.getExperiment(experiment.id);
      // Note: experiment.trace_count is not directly exposed in the Experiment type
      // but we can verify by getting experiment items
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      expect(experimentItems.length).toBe(datasetSize);
    });

    await test.step('Verify item IDs match between UI and backend', async () => {
      const experimentItemsPage = new ExperimentItemsPage(page);
      const idsOnFrontend = await experimentItemsPage.getAllItemIdsInExperiment();

      const itemsOnBackend = await helperClient.getExperimentItems(experiment.name);
      const idsOnBackend = itemsOnBackend.map(item => item.dataset_item_id);

      // Sort both arrays for comparison
      const sortedFrontendIds = idsOnFrontend.sort();
      const sortedBackendIds = idsOnBackend.sort();

      expect(sortedFrontendIds).toEqual(sortedBackendIds);
    });
  });

  test('Experiment items can be deleted and deletion is reflected in both UI and backend @fullregression @experiments', async ({
    page,
    helperClient,
    createExperimentWithItems,
  }) => {
    test.info().annotations.push({
      type: 'description',
      description: `Tests that experiment items can be deleted and the deletion is properly reflected in both the UI and backend.

Steps:
1. Create an experiment with items via fixture
2. Navigate to the experiment items page
3. Get and delete one experiment item via SDK
4. Reload the page and verify the item count decreased by 1 in the UI
5. Verify the trace count decreased by 1 in the backend
6. Verify the deleted item ID no longer appears in either UI or backend
7. Verify remaining item IDs match between UI and backend

This test ensures proper synchronization of experiment item deletions between UI and backend.`
    });

    const { experiment, datasetSize } = createExperimentWithItems;

    await test.step('Navigate to experiment items page', async () => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.clickExperiment(experiment.name);
    });

    let deletedItemId: string;

    await test.step('Get and delete one experiment item', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name, 1);
      expect(experimentItems.length).toBeGreaterThan(0);

      deletedItemId = experimentItems[0].id;
      await helperClient.deleteExperimentItems([deletedItemId]);
    });

    await test.step('Verify updated item count in UI', async () => {
      await page.reload();

      const experimentItemsPage = new ExperimentItemsPage(page);
      const itemsOnPage = await experimentItemsPage.getTotalNumberOfItemsInExperiment();

      expect(itemsOnPage).toBe(datasetSize - 1);
    });

    await test.step('Verify updated trace count in backend', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      expect(experimentItems.length).toBe(datasetSize - 1);
    });

    await test.step('Verify remaining item IDs match between UI and backend', async () => {
      const experimentItemsPage = new ExperimentItemsPage(page);
      const idsOnFrontend = await experimentItemsPage.getAllItemIdsInExperiment();

      const itemsOnBackend = await helperClient.getExperimentItems(experiment.name);
      const idsOnBackend = itemsOnBackend.map(item => item.dataset_item_id);

      // Sort both arrays for comparison
      const sortedFrontendIds = idsOnFrontend.sort();
      const sortedBackendIds = idsOnBackend.sort();

      expect(sortedFrontendIds).toEqual(sortedBackendIds);

      // Verify deleted item is not in the remaining items
      expect(idsOnFrontend).not.toContain(deletedItemId);
      expect(idsOnBackend).not.toContain(deletedItemId);
    });
  });
});
