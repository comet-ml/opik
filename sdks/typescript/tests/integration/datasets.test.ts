/**
 * Integration test for Dataset CRUD operations in the TypeScript SDK.
 * This is a sanity check for the happy path of basic CRUD operations.
 * More extensive edge cases and error handling are covered in unit tests.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { Dataset } from "@/dataset/Dataset";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Dataset CRUD Integration Test", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterAll(async () => {
    if (!client) {
      return;
    }

    // Cleanup all created datasets
    for (const name of createdDatasetNames) {
      try {
        await client.deleteDataset(name);
        await client.flush();
      } catch (error) {
        // Ignore errors during cleanup
        console.warn(`Failed to cleanup dataset "${name}":`, error);
      }
    }
  });

  it("should perform complete CRUD flow: create, read, update, delete", async () => {
    const testDatasetName = `test-dataset-crud-${Date.now()}`;
    const description = "Test dataset for CRUD operations";

    // CREATE: Create a new dataset
    const dataset = await client.createDataset(testDatasetName, description);
    createdDatasetNames.push(testDatasetName);
    await client.flush();

    expect(dataset).toBeInstanceOf(Dataset);
    expect(dataset.name).toBe(testDatasetName);
    expect(dataset.description).toBe(description);
    const datasetId = dataset.id;
    expect(datasetId).toBeDefined();

    // Verify dataset is available via search
    const datasets = await client.getDatasets();
    const datasetsFound = datasets.filter((d) => d.name === testDatasetName);
    expect(datasetsFound.length).toBeGreaterThanOrEqual(1);

    // READ: Retrieve the dataset by name
    const retrievedDataset = await client.getDataset(testDatasetName);
    expect(retrievedDataset).toBeInstanceOf(Dataset);
    expect(retrievedDataset.name).toBe(testDatasetName);
    expect(retrievedDataset.description).toBe(description);
    expect(retrievedDataset.id).toBe(datasetId);

    // INSERT: Add items to the dataset
    await dataset.insert([
      { input: "Hello", output: "Hi" },
      { input: "Goodbye", output: "Bye" },
    ]);

    // Verify items are available
    const itemsFound = await dataset.getItems();
    expect(itemsFound.length).toBe(2);

    // UPDATE: Update an item
    const items = await dataset.getItems();
    expect(items.length).toBe(2);

    const idToUpdate = items[0].id;
    const itemToUpdate = {
      id: idToUpdate,
      input: items[0].input,
      output: "Updated output",
    };
    await dataset.update([itemToUpdate]);

    // Verify update by fetching all items
    const allItemsAfterUpdate = await dataset.getItems();
    expect(allItemsAfterUpdate.length).toBe(2);
    const updatedItem = allItemsAfterUpdate.find((item) => item.id === idToUpdate);
    expect(updatedItem).toBeDefined();
    expect(updatedItem!.output).toBe("Updated output");

    // DELETE: Delete the dataset
    await client.deleteDataset(testDatasetName);
    await client.flush();

    // Verify deletion by attempting to fetch (should throw)
    await expect(client.getDataset(testDatasetName)).rejects.toThrow();

    // Remove from cleanup list since we already deleted it
    const index = createdDatasetNames.indexOf(testDatasetName);
    if (index > -1) {
      createdDatasetNames.splice(index, 1);
    }
  });
});
