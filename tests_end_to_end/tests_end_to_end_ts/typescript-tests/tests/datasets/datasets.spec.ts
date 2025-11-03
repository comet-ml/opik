import { test, expect } from '../../fixtures/datasets.fixture';
import { DatasetsPage } from '../../page-objects/datasets.page';
import { generateDatasetName } from '../../helpers/random';

test.describe('Datasets CRUD Tests', () => {
  test.describe('with SDK-created datasets', () => {
    test('Datasets created via SDK are visible in both UI and SDK @sanity @happypaths @fullregression @datasets', async ({ page, helperClient, createDatasetSdk }) => {
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
