import { test, expect } from '../../fixtures/datasets.fixture';
import { DatasetsPage } from '../../page-objects/datasets.page';
import { generateDatasetName } from '../../helpers/random';

test.describe('Datasets CRUD Tests', () => {
  test.describe('with SDK-created datasets', () => {
    test('Datasets created via SDK are visible in both UI and SDK @sanity @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetSdk }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via the SDK are properly visible and accessible in both the UI and SDK interfaces.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Navigate to the datasets page in the UI
3. Verify the dataset appears in the UI list
4. Query the dataset via SDK
5. Verify the dataset can be retrieved and has the correct name

This test ensures proper synchronization between UI and backend after SDK-based dataset creation.`
      });

      await test.step('Verify dataset is visible in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetExists(createDatasetSdk);
      });

      await test.step('Verify dataset is retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetSdk);
        expect(dataset).not.toBeNull();
        expect(dataset?.name).toBe(createDatasetSdk);
      });
    });

    test('SDK-created datasets can be renamed via SDK with changes reflected in UI @fullregression @datasets', async ({ page, helperClient, createDatasetSdk }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via SDK can be renamed through the SDK, and the name change is properly reflected in the UI.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Generate a new unique name for the dataset
3. Update the dataset name via SDK
4. Verify the dataset can be found by the new name via SDK
5. Navigate to the UI and verify the new name appears
6. Verify the old name no longer appears in the UI

This test ensures name updates propagate correctly from SDK to UI.`
      });

      const newName = generateDatasetName();
      let nameUpdated = false;
      let datasetId: string | undefined;

      try {
        await test.step('Update dataset name via SDK', async () => {
          const updatedDataset = await helperClient.updateDataset(createDatasetSdk, newName);
          nameUpdated = true;
          datasetId = updatedDataset.id;
          expect(datasetId).toBeDefined();
        });

        await test.step('Verify updated name is reflected in SDK', async () => {
          await helperClient.waitForDatasetVisible(newName, 10);
          const dataset = await helperClient.findDataset(newName);
          const datasetIdUpdatedName = dataset?.id;
          expect(datasetIdUpdatedName).toBe(datasetId);
        });

        await test.step('Verify updated name is reflected in UI', async () => {
          const datasetsPage = new DatasetsPage(page);
          await datasetsPage.goto();
          await datasetsPage.checkDatasetExists(newName);
          await datasetsPage.checkDatasetNotExists(createDatasetSdk);
        });
      } finally {
        if (nameUpdated) {
          await helperClient.deleteDataset(newName);
        } else {
          await helperClient.deleteDataset(createDatasetSdk);
        }
      }
    });

    test('SDK-created datasets can be deleted via SDK with changes reflected in UI @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetSdk }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via SDK can be deleted through the SDK, and the deletion is properly reflected in the UI.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Delete the dataset via SDK
3. Navigate to the UI and verify the dataset no longer appears
4. Attempt to retrieve the dataset via SDK and verify it returns null

This test ensures deletions propagate correctly from SDK to UI.`
      });

      await test.step('Delete dataset via SDK', async () => {
        await helperClient.deleteDataset(createDatasetSdk);
      });

      await test.step('Verify dataset is not visible in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetNotExists(createDatasetSdk);
      });

      await test.step('Verify dataset is not retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetSdk);
        expect(dataset).toBeNull();
      });
    });

    test('SDK-created datasets can be deleted via UI with changes reflected in SDK @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetSdk }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via SDK can be deleted through the UI, and the deletion is properly reflected in the SDK.

Steps:
1. Create a dataset via SDK (handled by fixture)
2. Navigate to the UI and delete the dataset using the UI delete action
3. Attempt to retrieve the dataset via SDK and verify it returns null

This test ensures UI deletions propagate correctly to the backend and SDK.`
      });

      await test.step('Delete dataset via UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.deleteDatasetByName(createDatasetSdk);
      });

      await test.step('Verify dataset is not retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetSdk);
        expect(dataset).toBeNull();
      });
    });
  });

  test.describe('with UI-created datasets', () => {
    test('Datasets created via UI are visible in both UI and SDK @sanity @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetUi }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via the UI are properly visible and accessible in both the UI and SDK interfaces.

Steps:
1. Create a dataset via UI (handled by fixture)
2. Verify the dataset appears in the UI list
3. Query the dataset via SDK
4. Verify the dataset can be retrieved and has the correct name

This test ensures proper synchronization between UI and backend after UI-based dataset creation.`
      });

      await test.step('Verify dataset is visible in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetExists(createDatasetUi);
      });

      await test.step('Verify dataset is retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetUi);
        expect(dataset).not.toBeNull();
        expect(dataset?.name).toBe(createDatasetUi);
      });
    });

    test('UI-created datasets can be renamed via SDK with changes reflected in UI @fullregression @datasets', async ({ page, helperClient, createDatasetUi }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via UI can be renamed through the SDK, and the name change is properly reflected back in the UI.

Steps:
1. Create a dataset via UI (handled by fixture)
2. Generate a new unique name for the dataset
3. Update the dataset name via SDK
4. Verify the dataset can be found by the new name via SDK
5. Navigate to the UI and verify the new name appears
6. Verify the old name no longer appears in the UI

This test ensures name updates from SDK are reflected in UI for UI-created datasets.`
      });

      const newName = generateDatasetName();
      let nameUpdated = false;
      let datasetId: string | undefined;

      try {
        await test.step('Update dataset name via SDK', async () => {
          const updatedDataset = await helperClient.updateDataset(createDatasetUi, newName);
          nameUpdated = true;
          datasetId = updatedDataset.id;
          expect(datasetId).toBeDefined();
        });

        await test.step('Verify updated name is reflected in SDK', async () => {
          await helperClient.waitForDatasetVisible(newName, 10);
          const dataset = await helperClient.findDataset(newName);
          const datasetIdUpdatedName = dataset?.id;
          expect(datasetIdUpdatedName).toBe(datasetId);
        });

        await test.step('Verify updated name is reflected in UI', async () => {
          const datasetsPage = new DatasetsPage(page);
          await datasetsPage.goto();
          await datasetsPage.checkDatasetExists(newName);
          await datasetsPage.checkDatasetNotExists(createDatasetUi);
        });
      } finally {
        if (nameUpdated) {
          await helperClient.deleteDataset(newName);
        } else {
          await helperClient.deleteDataset(createDatasetUi);
        }
      }
    });

    test('UI-created datasets can be deleted via SDK with changes reflected in UI @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetUi }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via UI can be deleted through the SDK, and the deletion is properly reflected in the UI.

Steps:
1. Create a dataset via UI (handled by fixture)
2. Delete the dataset via SDK
3. Navigate to the UI and verify the dataset no longer appears
4. Attempt to retrieve the dataset via SDK and verify it returns null

This test ensures SDK deletions are reflected in UI for UI-created datasets.`
      });

      await test.step('Delete dataset via SDK', async () => {
        await helperClient.deleteDataset(createDatasetUi);
      });

      await test.step('Verify dataset is not visible in UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetNotExists(createDatasetUi);
      });

      await test.step('Verify dataset is not retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetUi);
        expect(dataset).toBeNull();
      });
    });

    test('UI-created datasets can be deleted via UI with changes reflected in SDK @fullregression @datasets', async ({ page, helperClient, createDatasetUi }) => {
      test.info().annotations.push({
        type: 'description',
        description: `Tests that datasets created via UI can be deleted through the UI, and the deletion is properly reflected in the SDK.

Steps:
1. Create a dataset via UI (handled by fixture)
2. Delete the dataset using the UI delete action
3. Attempt to retrieve the dataset via SDK and verify it returns null

This test ensures UI deletions propagate correctly to the backend for UI-created datasets.`
      });

      await test.step('Delete dataset via UI', async () => {
        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.deleteDatasetByName(createDatasetUi);
      });

      await test.step('Verify dataset is not retrievable via SDK', async () => {
        const dataset = await helperClient.findDataset(createDatasetUi);
        expect(dataset).toBeNull();
      });
    });
  });
});
