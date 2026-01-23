/**
 * Integration test for Dataset CRUD operations in the TypeScript SDK.
 * This is a sanity check for the happy path of basic CRUD operations.
 * More extensive edge cases and error handling are covered in unit tests.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { Dataset } from "@/dataset/Dataset";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
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
    const createdDataset = await client.createDataset(
      testDatasetName,
      description
    );
    createdDatasetNames.push(testDatasetName);
    await client.flush();

    expect(createdDataset).toBeInstanceOf(Dataset);
    expect(createdDataset.name).toBe(testDatasetName);
    expect(createdDataset.description).toBe(description);
    const datasetId = createdDataset.id;
    expect(datasetId).toBeDefined();

    // Wait for dataset to be available via search
    const datasetsFound = await searchAndWaitForDone(
      async () => {
        const datasets = await client.getDatasets();
        return datasets.filter((d) => d.name === testDatasetName);
      },
      1, // Wait for at least 1 dataset
      10000, // 10 second timeout
      1000 // 1 second poll interval
    );
    expect(datasetsFound.length).toBeGreaterThanOrEqual(1);

    // READ: Retrieve the dataset by name (fresh fetch, not reusing object)
    const retrievedDataset = await client.getDataset(testDatasetName);
    expect(retrievedDataset).toBeInstanceOf(Dataset);
    expect(retrievedDataset.name).toBe(testDatasetName);
    expect(retrievedDataset.description).toBe(description);
    expect(retrievedDataset.id).toBe(datasetId);

    // INSERT: Add items to the dataset
    await retrievedDataset.insert([
      { input: "Hello", output: "Hi" },
      { input: "Goodbye", output: "Bye" },
    ]);

    // Wait for items to be available
    const itemsFound = await searchAndWaitForDone(
      async () => {
        const freshDataset = await client.getDataset(testDatasetName);
        return await freshDataset.getItems();
      },
      2, // Wait for at least 2 items
      10000, // 10 second timeout
      1000 // 1 second poll interval
    );
    expect(itemsFound.length).toBe(2);

    // UPDATE: Fetch dataset again and update an item
    const datasetForUpdate = await client.getDataset(testDatasetName);
    const items = await datasetForUpdate.getItems();
    expect(items.length).toBe(2);

    const itemToUpdate = {
      id: items[0].id,
      input: items[0].input,
      output: "Updated output",
    };
    await datasetForUpdate.update([itemToUpdate]);

    // Verify update by fetching again
    const updatedItems = await searchAndWaitForDone(
      async () => {
        const freshDataset = await client.getDataset(testDatasetName);
        const allItems = await freshDataset.getItems();
        return allItems.filter((item) => item.output === "Updated output");
      },
      1, // Wait for at least 1 updated item
      10000, // 10 second timeout
      1000 // 1 second poll interval
    );
    expect(updatedItems.length).toBeGreaterThanOrEqual(1);
    expect(updatedItems[0].output).toBe("Updated output");

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
