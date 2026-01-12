import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { Dataset } from "@/dataset/Dataset";
import { DatasetItemData } from "@/dataset/DatasetItem";
import { OpikClient } from "@/client/Client";
import { DatasetItemMissingIdError } from "@/errors";
import {
  JsonItemNotObjectError,
  JsonNotArrayError,
  JsonParseError,
} from "@/errors/common/errors";
import { logger } from "@/utils/logger";
import { mockAPIFunction, mockAPIFunctionWithStream } from "../mockUtils";
import { DatasetItemWriteSource } from "@/rest_api/api";

describe("Dataset entity operations", () => {
  let opikClient: OpikClient;
  let dataset: Dataset;
  let createOrUpdateDatasetItemsSpy: MockInstance;
  let deleteDatasetItemsSpy: MockInstance;
  let streamDatasetItemsSpy: MockInstance;
  let loggerInfoSpy: MockInstance<typeof logger.info>;

  beforeEach(() => {
    // Create mock OpikClient
    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    // Create a test dataset
    dataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset for operations",
      },
      opikClient
    );

    // Mock API methods
    createOrUpdateDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "createOrUpdateDatasetItems")
      .mockImplementation(mockAPIFunction);

    deleteDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "deleteDatasetItems")
      .mockImplementation(mockAPIFunction);

    streamDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "streamDatasetItems")
      .mockImplementation(() => mockAPIFunctionWithStream("[]"));

    // Mock logger methods
    loggerInfoSpy = vi.spyOn(logger, "info");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("insert", () => {
    it("should insert items into the dataset", async () => {
      const items: DatasetItemData[] = [
        { id: "item-1", input: "test input 1", output: "test output 1" },
        { id: "item-2", input: "test input 2", output: "test output 2" },
      ];

      await dataset.insert(items);

      expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledWith({
        datasetId: "test-dataset-id",
        items: [
          expect.objectContaining({
            id: "item-1",
            data: expect.objectContaining({
              input: "test input 1",
              output: "test output 1",
            }),
            source: DatasetItemWriteSource.Sdk,
          }),
          expect.objectContaining({
            id: "item-2",
            data: expect.objectContaining({
              input: "test input 2",
              output: "test output 2",
            }),
            source: DatasetItemWriteSource.Sdk,
          }),
        ],
        batchGroupId: expect.any(String),
      });
    });

    it("should do nothing when inserting empty array", async () => {
      await dataset.insert([]);
      expect(createOrUpdateDatasetItemsSpy).not.toHaveBeenCalled();
    });

    it("should generate IDs for items without IDs", async () => {
      const items: DatasetItemData[] = [
        { input: "test input without id", output: "test output without id" },
      ];

      await dataset.insert(items);

      expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledWith({
        datasetId: "test-dataset-id",
        items: [
          expect.objectContaining({
            id: expect.any(String), // ID should be generated
            data: expect.objectContaining({
              input: "test input without id",
              output: "test output without id",
            }),
            source: DatasetItemWriteSource.Sdk,
          }),
        ],
        batchGroupId: expect.any(String),
      });
    });

    it("should use same batchGroupId for all batches in single insert", async () => {
      // Create 2500 items (requires 3 batches)
      const items = Array.from({ length: 2500 }, (_, i) => ({
        id: `item-${i}`,
        input: `input-${i}`,
      }));

      await dataset.insert(items);

      expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(3);

      const batchGroupId1 = createOrUpdateDatasetItemsSpy.mock.calls[0][0].batchGroupId;
      const batchGroupId2 = createOrUpdateDatasetItemsSpy.mock.calls[1][0].batchGroupId;
      const batchGroupId3 = createOrUpdateDatasetItemsSpy.mock.calls[2][0].batchGroupId;

      expect(batchGroupId1).toBeDefined();
      expect(batchGroupId1).toBe(batchGroupId2);
      expect(batchGroupId2).toBe(batchGroupId3);
    });

    it("should use different batchGroupIds for separate insert calls", async () => {
      await dataset.insert([{ id: "item-1", input: "input-1" }]);
      await dataset.insert([{ id: "item-2", input: "input-2" }]);

      expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(2);

      const batchGroupId1 = createOrUpdateDatasetItemsSpy.mock.calls[0][0].batchGroupId;
      const batchGroupId2 = createOrUpdateDatasetItemsSpy.mock.calls[1][0].batchGroupId;

      expect(batchGroupId1).not.toBe(batchGroupId2);
    });
  });

  describe("update", () => {
    it("should update existing items in dataset", async () => {
      const itemsToUpdate = [
        { id: "item-1", input: "updated input 1", output: "updated output 1" },
        { id: "item-2", input: "updated input 2", output: "updated output 2" },
      ];

      await dataset.update(itemsToUpdate);

      expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledWith({
        datasetId: "test-dataset-id",
        items: [
          expect.objectContaining({
            id: "item-1",
            data: expect.objectContaining({
              input: "updated input 1",
              output: "updated output 1",
            }),
            source: DatasetItemWriteSource.Sdk,
          }),
          expect.objectContaining({
            id: "item-2",
            data: expect.objectContaining({
              input: "updated input 2",
              output: "updated output 2",
            }),
            source: DatasetItemWriteSource.Sdk,
          }),
        ],
        batchGroupId: expect.any(String),
      });
    });

    it("should throw error when updating items without IDs", async () => {
      const itemsWithoutIds = [
        { input: "no id input", output: "no id output" },
      ];

      await expect(dataset.update(itemsWithoutIds)).rejects.toThrow(
        DatasetItemMissingIdError
      );
      expect(createOrUpdateDatasetItemsSpy).not.toHaveBeenCalled();
    });

    it("should do nothing when updating empty array", async () => {
      await dataset.update([]);
      expect(createOrUpdateDatasetItemsSpy).not.toHaveBeenCalled();
    });
  });

  describe("delete", () => {
    it("should delete items by ID", async () => {
      const itemIds = ["item-1", "item-2"];

      await dataset.delete(itemIds);

      expect(deleteDatasetItemsSpy).toHaveBeenCalledWith({
        itemIds: ["item-1", "item-2"],
        batchGroupId: expect.any(String),
      });
    });

    it("should do nothing when deleting empty array", async () => {
      await dataset.delete([]);
      expect(deleteDatasetItemsSpy).not.toHaveBeenCalled();
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "No item IDs provided for deletion"
      );
    });

    it("should process large batches in chunks", async () => {
      // Create array with 250 IDs (over the batch size of 100)
      const largeItemIdArray = Array.from(
        { length: 250 },
        (_, i) => `item-${i}`
      );

      await dataset.delete(largeItemIdArray);

      // Should be called 3 times (for batches of 100, 100, and 50)
      expect(deleteDatasetItemsSpy).toHaveBeenCalledTimes(3);

      // First batch should have items 0-99
      expect(deleteDatasetItemsSpy).toHaveBeenNthCalledWith(1, {
        itemIds: expect.arrayContaining(["item-0", "item-99"]),
        batchGroupId: expect.any(String),
      });
      expect(deleteDatasetItemsSpy.mock.calls[0][0].itemIds.length).toBe(100);

      // Second batch should have items 100-199
      expect(deleteDatasetItemsSpy).toHaveBeenNthCalledWith(2, {
        itemIds: expect.arrayContaining(["item-100", "item-199"]),
        batchGroupId: expect.any(String),
      });
      expect(deleteDatasetItemsSpy.mock.calls[1][0].itemIds.length).toBe(100);

      // Third batch should have items 200-249
      expect(deleteDatasetItemsSpy).toHaveBeenNthCalledWith(3, {
        itemIds: expect.arrayContaining(["item-200", "item-249"]),
        batchGroupId: expect.any(String),
      });
      expect(deleteDatasetItemsSpy.mock.calls[2][0].itemIds.length).toBe(50);

      // All batches should have the same batchGroupId
      const batchGroupId1 = deleteDatasetItemsSpy.mock.calls[0][0].batchGroupId;
      const batchGroupId2 = deleteDatasetItemsSpy.mock.calls[1][0].batchGroupId;
      const batchGroupId3 = deleteDatasetItemsSpy.mock.calls[2][0].batchGroupId;
      expect(batchGroupId1).toBe(batchGroupId2);
      expect(batchGroupId2).toBe(batchGroupId3);
    });

    it("should use different batchGroupIds for separate delete calls", async () => {
      await dataset.delete(["item-1"]);
      await dataset.delete(["item-2"]);

      expect(deleteDatasetItemsSpy).toHaveBeenCalledTimes(2);

      const batchGroupId1 = deleteDatasetItemsSpy.mock.calls[0][0].batchGroupId;
      const batchGroupId2 = deleteDatasetItemsSpy.mock.calls[1][0].batchGroupId;

      expect(batchGroupId1).not.toBe(batchGroupId2);
    });
  });

  describe("clear", () => {
    it("should clear all items from dataset", async () => {
      // Mock getItems to return test data
      const mockItems = [
        { id: "item-1", input: "test input 1", output: "test output 1" },
        { id: "item-2", input: "test input 2", output: "test output 2" },
      ];

      // Mock the getItems method
      vi.spyOn(dataset, "getItems").mockResolvedValueOnce(mockItems);

      // Spy on the delete method
      const deleteSpy = vi.spyOn(dataset, "delete");

      await dataset.clear();

      // Verify the delete method was called with the correct item IDs
      expect(deleteSpy).toHaveBeenCalledWith(["item-1", "item-2"]);
    });

    it("should do nothing when dataset has no items", async () => {
      // Return empty array from getItems
      vi.spyOn(dataset, "getItems").mockResolvedValueOnce([]);

      // Spy on the delete method
      const deleteSpy = vi.spyOn(dataset, "delete");

      await dataset.clear();

      // The delete method should not be called when there are no items
      expect(deleteSpy).not.toHaveBeenCalled();
    });
  });

  describe("getItems", () => {
    // Skip complex testing of internal logic, focus on the API interface
    it("should request items with correct parameters", async () => {
      // Set up a minimal mock to avoid errors
      streamDatasetItemsSpy.mockReturnValueOnce(
        mockAPIFunctionWithStream("[]")
      );

      await dataset.getItems(50);

      // Verify the API was called with correct parameters
      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: undefined,
        steamLimit: 50,
      });
    });

    it("should cap the request limit to API maximum", async () => {
      // Set up a minimal mock to avoid errors
      streamDatasetItemsSpy.mockReturnValueOnce(
        mockAPIFunctionWithStream("[]")
      );

      await dataset.getItems(3000);

      // Verify the API request limit is capped
      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: undefined,
        steamLimit: 2000, // Should be capped at 2000
      });
    });

    it("should use lastRetrievedId for pagination", async () => {
      // Set up a minimal mock to avoid errors
      streamDatasetItemsSpy.mockReturnValueOnce(
        mockAPIFunctionWithStream("[]")
      );

      await dataset.getItems(10, "last-item-id");

      // Verify pagination parameter is passed correctly
      expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
        datasetName: "test-dataset",
        lastRetrievedId: "last-item-id", // Should use the provided last item ID
        steamLimit: 10,
      });
    });
  });

  describe("insertFromJson", () => {
    it("should insert items from valid JSON string", async () => {
      const jsonString = JSON.stringify([
        { input: "test input 1", output: "test output 1" },
        { input: "test input 2", output: "test output 2" },
      ]);

      // Mock the insert method
      const insertSpy = vi.spyOn(dataset, "insert");
      insertSpy.mockResolvedValueOnce();

      await dataset.insertFromJson(jsonString);

      expect(insertSpy).toHaveBeenCalledWith([
        expect.objectContaining({
          input: "test input 1",
          output: "test output 1",
        }),
        expect.objectContaining({
          input: "test input 2",
          output: "test output 2",
        }),
      ]);
    });

    it("should apply key mappings when inserting from JSON", async () => {
      const jsonString = JSON.stringify([
        {
          "Original Input": "mapped input 1",
          "Expected Result": "mapped output 1",
        },
        {
          "Original Input": "mapped input 2",
          "Expected Result": "mapped output 2",
        },
      ]);

      const keyMapping = {
        "Original Input": "input",
        "Expected Result": "expected_output",
      };

      // Mock the insert method
      const insertSpy = vi.spyOn(dataset, "insert");
      insertSpy.mockResolvedValueOnce();

      await dataset.insertFromJson(jsonString, keyMapping);

      expect(insertSpy).toHaveBeenCalledWith([
        expect.objectContaining({
          input: "mapped input 1",
          expected_output: "mapped output 1",
        }),
        expect.objectContaining({
          input: "mapped input 2",
          expected_output: "mapped output 2",
        }),
      ]);
    });

    it("should ignore specified keys when inserting from JSON", async () => {
      const jsonString = JSON.stringify([
        { input: "test input", output: "test output", metadata: "ignore me" },
      ]);

      // Mock the insert method
      const insertSpy = vi.spyOn(dataset, "insert");
      insertSpy.mockResolvedValueOnce();

      await dataset.insertFromJson(jsonString, {}, ["metadata"]);

      expect(insertSpy).toHaveBeenCalledWith([
        expect.objectContaining({
          input: "test input",
          output: "test output",
        }),
      ]);

      // Verify metadata was ignored
      expect(insertSpy.mock.calls[0][0][0]).not.toHaveProperty("metadata");
    });

    it("should handle empty JSON array", async () => {
      const jsonString = "[]";

      // Mock the insert method
      const insertSpy = vi.spyOn(dataset, "insert");

      await dataset.insertFromJson(jsonString);

      expect(insertSpy).not.toHaveBeenCalled();
    });

    it("should throw error for invalid JSON string", async () => {
      const invalidJson = "{malformed: json}";

      await expect(dataset.insertFromJson(invalidJson)).rejects.toThrow(
        JsonParseError
      );
    });

    it("should throw error when JSON is not an array", async () => {
      const notArrayJson = JSON.stringify({ key: "value" });

      await expect(dataset.insertFromJson(notArrayJson)).rejects.toThrow(
        JsonNotArrayError
      );
    });

    it("should throw error when array contains non-object items", async () => {
      const invalidItemsJson = JSON.stringify([
        "string",
        123,
        { valid: "object" },
      ]);

      await expect(dataset.insertFromJson(invalidItemsJson)).rejects.toThrow(
        JsonItemNotObjectError
      );
    });
  });

  describe("toJson", () => {
    it("should convert dataset items to JSON string", async () => {
      // Mock getItems to return our test data
      const mockItems = [
        { id: "item-1", input: "test input 1", output: "test output 1" },
        { id: "item-2", input: "test input 2", output: "test output 2" },
      ];

      vi.spyOn(dataset, "getItems").mockResolvedValueOnce(mockItems);

      const jsonString = await dataset.toJson();
      const parsedJson = JSON.parse(jsonString);

      expect(Array.isArray(parsedJson)).toBe(true);
      expect(parsedJson).toHaveLength(2);
      expect(parsedJson[0]).toHaveProperty("input", "test input 1");
      expect(parsedJson[0]).toHaveProperty("output", "test output 1");
      expect(parsedJson[1]).toHaveProperty("input", "test input 2");
      expect(parsedJson[1]).toHaveProperty("output", "test output 2");
    });

    it("should apply key mappings when converting to JSON", async () => {
      // Mock getItems to return our test data
      const mockItems = [
        { id: "item-1", input: "test input 1", output: "test output 1" },
        { id: "item-2", input: "test input 2", output: "test output 2" },
      ];

      vi.spyOn(dataset, "getItems").mockResolvedValueOnce(mockItems);

      const keyMapping = {
        input: "question",
        output: "answer",
      };

      const jsonString = await dataset.toJson(keyMapping);
      const parsedJson = JSON.parse(jsonString);

      expect(parsedJson[0]).toHaveProperty("question", "test input 1");
      expect(parsedJson[0]).toHaveProperty("answer", "test output 1");
      expect(parsedJson[0]).not.toHaveProperty("input");
      expect(parsedJson[0]).not.toHaveProperty("output");

      expect(parsedJson[1]).toHaveProperty("question", "test input 2");
      expect(parsedJson[1]).toHaveProperty("answer", "test output 2");
    });

    it("should handle empty dataset", async () => {
      // Mock getItems to return empty array
      vi.spyOn(dataset, "getItems").mockResolvedValueOnce([]);

      const jsonString = await dataset.toJson();
      const parsedJson = JSON.parse(jsonString);

      expect(Array.isArray(parsedJson)).toBe(true);
      expect(parsedJson).toHaveLength(0);
    });
  });
});
