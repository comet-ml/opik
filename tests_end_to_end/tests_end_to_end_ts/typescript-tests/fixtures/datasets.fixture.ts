import { test as base, BaseFixtures } from './base.fixture';
import { DatasetsPage } from '../page-objects/datasets.page';

export type DatasetsFixtures = {
  createDatasetSdk: string;
  createDatasetUi: string;
  datasetsPage: DatasetsPage;
};

export const test = base.extend<BaseFixtures & DatasetsFixtures>({
  createDatasetSdk: async ({ helperClient, datasetName }, use) => {
    const existingDataset = await helperClient.findDataset(datasetName);
    if (existingDataset) {
      await helperClient.deleteDataset(datasetName);
    }

    await helperClient.createDataset(datasetName);
    await helperClient.waitForDatasetVisible(datasetName);
    await use(datasetName);

    try {
      const dataset = await helperClient.findDataset(datasetName);
      if (dataset) {
        await helperClient.deleteDataset(datasetName);
      }
    } catch (error) {
      console.warn(`Failed to cleanup dataset ${datasetName}:`, error);
    }
  },

  createDatasetUi: async ({ page, helperClient, datasetName }, use) => {
    const existingDataset = await helperClient.findDataset(datasetName);
    if (existingDataset) {
      await helperClient.deleteDataset(datasetName);
    }

    const datasetsPage = new DatasetsPage(page);
    await datasetsPage.goto();
    await datasetsPage.createDatasetByName(datasetName);

    // Wait for the dataset to be visible in the backend after UI creation
    await helperClient.waitForDatasetVisible(datasetName);

    await use(datasetName);

    try {
      const datasetToCleanup = await helperClient.findDataset(datasetName);
      if (datasetToCleanup) {
        await datasetsPage.goto();
        await datasetsPage.deleteDatasetByName(datasetName);
        await helperClient.waitForDatasetDeleted(datasetName);
      }
    } catch (error) {
      console.warn(`Failed to cleanup dataset ${datasetName}:`, error);
    }
  },

  datasetsPage: async ({ page }, use) => {
    const datasetsPage = new DatasetsPage(page);
    await datasetsPage.goto();
    await use(datasetsPage);
  },
});

export { expect } from '@playwright/test';
