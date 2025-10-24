import { test, expect } from '../../fixtures/datasets.fixture';
import { DatasetsPage } from '../../page-objects/datasets.page';
import { generateDatasetName } from '../../helpers/random';

test.describe('Datasets CRUD Tests', () => {
  test.describe('with SDK-created datasets', () => {
    test('should verify dataset visibility', async ({ page, helperClient, createDatasetSdk }) => {
      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.checkDatasetExists(createDatasetSdk);

      const dataset = await helperClient.findDataset(createDatasetSdk);
      expect(dataset).not.toBeNull();
      expect(dataset?.name).toBe(createDatasetSdk);
    });

    test('should update dataset name via SDK', async ({ page, helperClient, createDatasetSdk }) => {
      const newName = generateDatasetName();
      let nameUpdated = false;

      try {
        const updatedDataset = await helperClient.updateDataset(createDatasetSdk, newName);
        nameUpdated = true;
        const datasetId = updatedDataset.id;

        expect(datasetId).toBeDefined();

        await helperClient.waitForDatasetVisible(newName, 10);
        const dataset = await helperClient.findDataset(newName);
        const datasetIdUpdatedName = dataset?.id;

        expect(datasetIdUpdatedName).toBe(datasetId);

        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetExists(newName);
        await datasetsPage.checkDatasetNotExists(createDatasetSdk);
      } finally {
        if (nameUpdated) {
          await helperClient.deleteDataset(newName);
        } else {
          await helperClient.deleteDataset(createDatasetSdk);
        }
      }
    });

    test('should delete dataset via SDK', async ({ page, helperClient, createDatasetSdk }) => {
      await helperClient.deleteDataset(createDatasetSdk);

      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.checkDatasetNotExists(createDatasetSdk);

      const dataset = await helperClient.findDataset(createDatasetSdk);
      expect(dataset).toBeNull();
    });

    test('should delete dataset via UI', async ({ page, helperClient, createDatasetSdk }) => {
      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.deleteDatasetByName(createDatasetSdk);

      const dataset = await helperClient.findDataset(createDatasetSdk);
      expect(dataset).toBeNull();
    });
  });

  test.describe('with UI-created datasets', () => {
    test('should verify dataset visibility', async ({ page, helperClient, createDatasetUi }) => {
      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.checkDatasetExists(createDatasetUi);

      const dataset = await helperClient.findDataset(createDatasetUi);
      expect(dataset).not.toBeNull();
      expect(dataset?.name).toBe(createDatasetUi);
    });

    test('should update dataset name via SDK', async ({ page, helperClient, createDatasetUi }) => {
      const newName = generateDatasetName();
      let nameUpdated = false;

      try {
        const updatedDataset = await helperClient.updateDataset(createDatasetUi, newName);
        nameUpdated = true;
        const datasetId = updatedDataset.id;

        expect(datasetId).toBeDefined();

        await helperClient.waitForDatasetVisible(newName, 10);
        const dataset = await helperClient.findDataset(newName);
        const datasetIdUpdatedName = dataset?.id;

        expect(datasetIdUpdatedName).toBe(datasetId);

        const datasetsPage = new DatasetsPage(page);
        await datasetsPage.goto();
        await datasetsPage.checkDatasetExists(newName);
        await datasetsPage.checkDatasetNotExists(createDatasetUi);
      } finally {
        if (nameUpdated) {
          await helperClient.deleteDataset(newName);
        } else {
          await helperClient.deleteDataset(createDatasetUi);
        }
      }
    });

    test('should delete dataset via SDK', async ({ page, helperClient, createDatasetUi }) => {
      await helperClient.deleteDataset(createDatasetUi);

      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.checkDatasetNotExists(createDatasetUi);

      const dataset = await helperClient.findDataset(createDatasetUi);
      expect(dataset).toBeNull();
    });

    test('should delete dataset via UI', async ({ page, helperClient, createDatasetUi }) => {
      const datasetsPage = new DatasetsPage(page);
      await datasetsPage.goto();
      await datasetsPage.deleteDatasetByName(createDatasetUi);

      const dataset = await helperClient.findDataset(createDatasetUi);
      expect(dataset).toBeNull();
    });
  });
});
