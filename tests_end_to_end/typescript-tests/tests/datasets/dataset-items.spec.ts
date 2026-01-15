import { test, expect } from '../../fixtures/datasets.fixture';
import { DatasetsPage } from '../../page-objects/datasets.page';
import { DatasetItemsPage } from '../../page-objects/dataset-items.page';
import { TEST_ITEMS, TEST_ITEMS_UPDATE, compareItemLists, getUpdatedItems } from './dataset-test-data';

test.describe('Dataset Items CRUD Tests', () => {
  test.describe('Dataset item insertion', () => {
    // Test combinations: SDK dataset creation + SDK item insertion
    test('Dataset items can be inserted via SDK into SDK-created dataset @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetSdk,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that dataset items can be inserted via SDK into an SDK-created dataset and are visible in both UI and SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Insert test items via SDK
3. Wait for items to be visible
4. Verify items are retrievable via SDK with correct content
5. Navigate to the UI and verify items appear with correct content
6. Verify item lists match between UI and SDK

This test ensures SDK item insertion works correctly for SDK-created datasets.`
      });

      await test.step('Insert items via SDK', async () => {
        await helperClient.insertDatasetItems(createDatasetSdk, TEST_ITEMS);
      });

      await test.step('Wait for items to be visible', async () => {
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length, 15);
      });

      await test.step('Verify items in SDK', async () => {
        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetSdk);
        expect(itemsFromSdk).toHaveLength(TEST_ITEMS.length);
        expect(compareItemLists(TEST_ITEMS, itemsFromSdk)).toBe(true);
      });

      await test.step('Verify items in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        const itemsFromUi = await datasetItemsPage.getAllItemsInDataset();

        expect(itemsFromUi).toHaveLength(TEST_ITEMS.length);
        expect(compareItemLists(TEST_ITEMS, itemsFromUi)).toBe(true);
      });
    });

    // Test combinations: UI dataset creation + SDK item insertion
    test('Dataset items can be inserted via SDK into UI-created dataset @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetUi,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that dataset items can be inserted via SDK into a UI-created dataset and are visible in both UI and SDK.

Steps:
1. Create a dataset via UI (handled by fixture)
2. Insert test items via SDK
3. Wait for items to be visible
4. Verify items are retrievable via SDK with correct content
5. Navigate to the UI and verify items appear with correct content
6. Verify item lists match between UI and SDK

This test ensures SDK item insertion works correctly for UI-created datasets.`
      });

      await test.step('Insert items via SDK', async () => {
        await helperClient.insertDatasetItems(createDatasetUi, TEST_ITEMS);
      });

      await test.step('Wait for items to be visible', async () => {
        await helperClient.waitForDatasetItemsCount(createDatasetUi, TEST_ITEMS.length, 15);
      });

      await test.step('Verify items in SDK', async () => {
        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetUi);
        expect(itemsFromSdk).toHaveLength(TEST_ITEMS.length);
        expect(compareItemLists(TEST_ITEMS, itemsFromSdk)).toBe(true);
      });

      await test.step('Verify items in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetUi);

        const datasetItemsPage = new DatasetItemsPage(page);
        const itemsFromUi = await datasetItemsPage.getAllItemsInDataset();

        expect(itemsFromUi).toHaveLength(TEST_ITEMS.length);
        expect(compareItemLists(TEST_ITEMS, itemsFromUi)).toBe(true);
      });
    });
  });

  test.describe('Dataset item updates', () => {
    test('Dataset items can be updated via SDK with changes reflected in both UI and SDK @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetSdk,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that dataset items can be updated via SDK and the changes are properly reflected in both UI and SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Insert initial test items via SDK
3. Update the items with new content via SDK
4. Verify updated items are retrievable via SDK with new content
5. Navigate to the UI and verify items appear with updated content
6. Verify item lists match between UI and SDK

This test ensures item updates via SDK propagate correctly to both UI and backend.`
      });

      await test.step('Insert initial items', async () => {
        await helperClient.insertDatasetItems(createDatasetSdk, TEST_ITEMS);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length, 15);
      });

      await test.step('Update items via SDK', async () => {
        const currentItems = await helperClient.getDatasetItems(createDatasetSdk);
        const updatedItems = getUpdatedItems(currentItems, TEST_ITEMS_UPDATE);
        await helperClient.updateDatasetItems(createDatasetSdk, updatedItems);
      });

      await test.step('Verify updated items in SDK', async () => {
        // Give a moment for updates to propagate
        await new Promise(resolve => setTimeout(resolve, 1000));

        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetSdk);
        expect(itemsFromSdk).toHaveLength(TEST_ITEMS_UPDATE.length);
        expect(compareItemLists(TEST_ITEMS_UPDATE, itemsFromSdk)).toBe(true);
      });

      await test.step('Verify updated items in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        const itemsFromUi = await datasetItemsPage.getAllItemsInDataset();

        expect(itemsFromUi).toHaveLength(TEST_ITEMS_UPDATE.length);
        expect(compareItemLists(TEST_ITEMS_UPDATE, itemsFromUi)).toBe(true);
      });
    });
  });

  test.describe('Dataset item deletion', () => {
    test('Dataset items can be deleted via SDK with changes reflected in both UI and SDK @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetSdk,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that dataset items can be deleted via SDK and the deletion is properly reflected in both UI and SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Insert test items via SDK
3. Delete one item via SDK
4. Verify the deleted item no longer exists in SDK
5. Verify item count decreased by 1 in SDK
6. Navigate to the UI and verify the deleted item is not present
7. Verify item count decreased by 1 in UI

This test ensures item deletions via SDK propagate correctly to both UI and backend.`
      });

      let deletedItemContent: Record<string, any>;

      await test.step('Insert items', async () => {
        await helperClient.insertDatasetItems(createDatasetSdk, TEST_ITEMS);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length, 15);
      });

      await test.step('Delete one item via SDK', async () => {
        const items = await helperClient.getDatasetItems(createDatasetSdk);
        const itemToDelete = items[0];
        deletedItemContent = { input: itemToDelete.input, output: itemToDelete.output };

        await helperClient.deleteDatasetItem(createDatasetSdk, itemToDelete.id);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length - 1, 15);
      });

      await test.step('Verify deletion in SDK', async () => {
        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetSdk);
        expect(itemsFromSdk).toHaveLength(TEST_ITEMS.length - 1);

        const deletedItemStillExists = itemsFromSdk.some(
          item => item.input === deletedItemContent.input && item.output === deletedItemContent.output
        );
        expect(deletedItemStillExists).toBe(false);
      });

      await test.step('Verify deletion in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        const itemsFromUi = await datasetItemsPage.getAllItemsInDataset();

        expect(itemsFromUi).toHaveLength(TEST_ITEMS.length - 1);

        const deletedItemStillExists = itemsFromUi.some(
          item => item.input === deletedItemContent.input && item.output === deletedItemContent.output
        );
        expect(deletedItemStillExists).toBe(false);
      });
    });

    test('Dataset items can be deleted via UI with changes reflected in both UI and SDK @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetSdk,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that dataset items can be deleted via UI and the deletion is properly reflected in both UI and SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Insert test items via SDK
3. Navigate to the UI and delete one item
4. Verify the deleted item no longer exists in SDK
5. Verify item count decreased by 1 in SDK
6. Verify the deleted item is not present in UI
7. Verify item count decreased by 1 in UI

This test ensures item deletions via UI propagate correctly to the backend.`
      });

      let deletedItemContent: Record<string, any>;

      await test.step('Insert items', async () => {
        await helperClient.insertDatasetItems(createDatasetSdk, TEST_ITEMS);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length, 15);
      });

      await test.step('Delete one item via UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        deletedItemContent = await datasetItemsPage.deleteFirstItemAndGetContent();

        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length - 1, 15);
      });

      await test.step('Verify deletion in SDK', async () => {
        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetSdk);
        expect(itemsFromSdk).toHaveLength(TEST_ITEMS.length - 1);

        const deletedItemStillExists = itemsFromSdk.some(
          item => item.input === deletedItemContent.input && item.output === deletedItemContent.output
        );
        expect(deletedItemStillExists).toBe(false);
      });

      await test.step('Verify deletion in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        const itemsFromUi = await datasetItemsPage.getAllItemsInDataset();

        expect(itemsFromUi).toHaveLength(TEST_ITEMS.length - 1);

        const deletedItemStillExists = itemsFromUi.some(
          item => item.input === deletedItemContent.input && item.output === deletedItemContent.output
        );
        expect(deletedItemStillExists).toBe(false);
      });
    });
  });

  test.describe('Dataset clear operation', () => {
    test('Datasets can be cleared via SDK with empty state reflected in both UI and SDK @fullregression @datasets', async ({
      page,
      helperClient,
      createDatasetSdk,
    }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets can be cleared via SDK and the empty state is properly reflected in both UI and SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Insert test items via SDK
3. Clear the dataset via SDK (removes all items)
4. Verify the dataset is empty in SDK
5. Navigate to the UI and verify the empty dataset message is displayed

This test ensures the clear operation removes all items and the empty state is reflected in both UI and backend.`
      });

      await test.step('Insert items', async () => {
        await helperClient.insertDatasetItems(createDatasetSdk, TEST_ITEMS);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, TEST_ITEMS.length, 15);
      });

      await test.step('Clear dataset via SDK', async () => {
        await helperClient.clearDataset(createDatasetSdk);
        await helperClient.waitForDatasetItemsCount(createDatasetSdk, 0, 15);
      });

      await test.step('Verify empty state in SDK', async () => {
        const itemsFromSdk = await helperClient.getDatasetItems(createDatasetSdk);
        expect(itemsFromSdk).toHaveLength(0);
      });

      await test.step('Verify empty state in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.selectDatasetByName(createDatasetSdk);

        const datasetItemsPage = new DatasetItemsPage(page);
        await datasetItemsPage.waitForEmptyDatasetMessage();
      });
    });
  });
});
