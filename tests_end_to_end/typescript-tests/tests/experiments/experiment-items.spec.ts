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
7. Verify "Go to traces" button navigates to experiment traces
8. Click an experiment item and verify trace details panel opens
9. Verify trace information is displayed correctly

This test ensures proper synchronization of experiment items between UI and backend, and validates trace navigation.`
    });

    const { experiment, datasetSize } = createExperimentWithItems;

    await test.step('Navigate to experiment items page', async () => {
      const experimentsPage = new ExperimentsPage(page);
      await experimentsPage.goto();
      await experimentsPage.clickExperiment(experiment.name);
    });

    await test.step('Verify item count in UI', async () => {
      const experimentItemsPage = new ExperimentItemsPage(page);
      await experimentItemsPage.initialize();
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
      await experimentItemsPage.initialize();
      const idsOnFrontend = await experimentItemsPage.getAllItemIdsInExperiment();

      const itemsOnBackend = await helperClient.getExperimentItems(experiment.name);
      const idsOnBackend = itemsOnBackend.map(item => item.dataset_item_id);

      // Sort both arrays for comparison
      const sortedFrontendIds = idsOnFrontend.sort();
      const sortedBackendIds = idsOnBackend.sort();

      expect(sortedFrontendIds).toEqual(sortedBackendIds);
    });

    await test.step('Verify "Go to traces" button navigates to experiment traces', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      const firstItem = experimentItems[0];

      const goToTracesButton = page.getByRole('link', { name: /Go to traces/i });
      await expect(goToTracesButton).toBeVisible();

      await goToTracesButton.click();
      await expect(page).toHaveURL(/\/traces/, { timeout: 10000 });
      
      const currentUrl = page.url();
      expect(currentUrl).toContain('experiment_id');

      const firstTraceRow = page.locator('table tbody tr').first();
      await expect(firstTraceRow).toBeVisible({ timeout: 10000 });

      const inputValue = firstItem.input?.input || firstItem.data?.input;
      if (inputValue) {
        const inputText = typeof inputValue === 'string' ? inputValue : JSON.stringify(inputValue);
        await expect(page.locator('table')).toContainText(inputText);
      }

      const outputValue = firstItem.output?.output || firstItem.output;
      if (outputValue) {
        const outputText = typeof outputValue === 'string' ? outputValue : JSON.stringify(outputValue);
        await expect(page.locator('table')).toContainText(outputText);
      }

      await page.goBack();
      await page.waitForLoadState('networkidle');
    });

    await test.step('Click experiment item and verify detail panel opens', async () => {
      const firstRow = page.locator('table tbody tr').first();
      await firstRow.click();

      const datasetItemHeading = page.getByRole('heading', { name: 'Dataset item', level: 4 });
      await expect(datasetItemHeading).toBeVisible({ timeout: 10000 });

      const traceButton = page.getByRole('button', { name: 'Trace' });
      await expect(traceButton).toBeVisible();

      await expect(page).toHaveURL(/row=/);
    });

    await test.step('Click Trace button and verify trace panel opens', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      const allTraceIds = experimentItems.map(item => item.trace_id);

      const traceButton = page.getByRole('button', { name: 'Trace' });
      await traceButton.click();

      await expect(page).toHaveURL(/trace=/, { timeout: 10000 });

      const currentUrl = new URL(page.url());
      const traceIdInUrl = currentUrl.searchParams.get('trace');
      
      expect(traceIdInUrl).toBeTruthy();
      expect(allTraceIds).toContain(traceIdInUrl);

      const detailsTab = page.getByRole('tab', { name: 'Details' });
      await expect(detailsTab).toBeVisible();
    });

    await test.step('Verify trace details match experiment item content', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      const currentUrl = new URL(page.url());
      const traceIdInUrl = currentUrl.searchParams.get('trace');
      
      const matchingItem = experimentItems.find(item => item.trace_id === traceIdInUrl);
      expect(matchingItem).toBeDefined();

      const traceTitle = page.locator('[data-testid="data-viewer-title"]').first();
      await expect(traceTitle).toBeVisible({ timeout: 10000 });

      const detailsTab = page.getByRole('tab', { name: 'Details' });
      await expect(detailsTab).toBeVisible();

      const inputOutputTab = page.getByRole('tab', { name: /Input.*Output/i }).first();
      if (await inputOutputTab.isVisible()) {
        await inputOutputTab.click();

        const tabPanel = page.locator('[role="tabpanel"]').first();
        await expect(tabPanel).toBeVisible();

        const inputValue = matchingItem?.input?.input || matchingItem?.data?.input;
        if (inputValue) {
          const inputText = typeof inputValue === 'string' ? inputValue : JSON.stringify(inputValue);
          await expect(tabPanel).toContainText(inputText);
        }

        const outputValue = matchingItem?.output?.output || matchingItem?.output;
        if (outputValue) {
          const outputText = typeof outputValue === 'string' ? outputValue : JSON.stringify(outputValue);
          await expect(tabPanel).toContainText(outputText);
        }
      }

      const feedbackScoresTab = page.getByRole('tab', { name: 'Feedback scores' }).first();
      await expect(feedbackScoresTab).toBeVisible();
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
      await experimentItemsPage.initialize();
      const itemsOnPage = await experimentItemsPage.getTotalNumberOfItemsInExperiment();

      expect(itemsOnPage).toBe(datasetSize - 1);
    });

    await test.step('Verify updated trace count in backend', async () => {
      const experimentItems = await helperClient.getExperimentItems(experiment.name);
      expect(experimentItems.length).toBe(datasetSize - 1);
    });

    await test.step('Verify remaining item IDs match between UI and backend', async () => {
      const experimentItemsPage = new ExperimentItemsPage(page);
      await experimentItemsPage.initialize();
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
