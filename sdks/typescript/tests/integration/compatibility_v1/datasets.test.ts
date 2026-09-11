/**
 * COMPATIBILITY V1 TEST — DO NOT MODIFY
 *
 * This is a frozen copy of the original datasets integration test from before
 * the projectName parameter was added to the SDK API. It ensures backward
 * compatibility: users who never specify projectName should experience zero
 * regressions.
 *
 * If you need to add new dataset tests, add them to the parent directory
 * (tests/integration/datasets.test.ts), not here.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { Dataset } from "@/dataset/Dataset";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "Compatibility V1: Dataset CRUD Integration Test",
  () => {
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

      for (const name of createdDatasetNames) {
        try {
          await client.deleteDataset(name);
          await client.flush();
        } catch (error) {
          console.warn(`Failed to cleanup dataset "${name}":`, error);
        }
      }
    });

    it("should perform complete CRUD flow: create, read, update, delete", async () => {
      const testDatasetName = `compat-v1-dataset-crud-${Date.now()}`;
      const description = "Compatibility V1 test dataset";

      // CREATE
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

      // LIST
      const datasetsFound = await searchAndWaitForDone(
        async () => {
          const datasets = await client.getDatasets();
          return datasets.filter((d) => d.name === testDatasetName);
        },
        1,
        10000,
        1000
      );
      expect(datasetsFound.length).toBeGreaterThanOrEqual(1);

      // READ
      const retrievedDataset = await client.getDataset(testDatasetName);
      expect(retrievedDataset).toBeInstanceOf(Dataset);
      expect(retrievedDataset.name).toBe(testDatasetName);
      expect(retrievedDataset.description).toBe(description);
      expect(retrievedDataset.id).toBe(datasetId);

      // INSERT ITEMS
      await retrievedDataset.insert([
        { input: "Hello", output: "Hi" },
        { input: "Goodbye", output: "Bye" },
      ]);

      const itemsFound = await searchAndWaitForDone(
        async () => {
          const freshDataset = await client.getDataset(testDatasetName);
          return await freshDataset.getItems();
        },
        2,
        10000,
        1000
      );
      expect(itemsFound.length).toBe(2);

      // UPDATE
      const datasetForUpdate = await client.getDataset(testDatasetName);
      const items = await datasetForUpdate.getItems();
      expect(items.length).toBe(2);

      const itemToUpdate = {
        id: items[0].id,
        input: items[0].input,
        output: "Updated output",
      };
      await datasetForUpdate.update([itemToUpdate]);

      const updatedItems = await searchAndWaitForDone(
        async () => {
          const freshDataset = await client.getDataset(testDatasetName);
          const allItems = await freshDataset.getItems();
          return allItems.filter((item) => item.output === "Updated output");
        },
        1,
        10000,
        1000
      );
      expect(updatedItems.length).toBeGreaterThanOrEqual(1);
      expect(updatedItems[0].output).toBe("Updated output");

      // DELETE
      await client.deleteDataset(testDatasetName);
      await client.flush();

      await expect(client.getDataset(testDatasetName)).rejects.toThrow();

      const index = createdDatasetNames.indexOf(testDatasetName);
      if (index > -1) {
        createdDatasetNames.splice(index, 1);
      }
    });
  }
);
