import { describe, it, expect, vi, beforeEach, MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetItemWrite } from "@/rest_api/api";
import { mockAPIFunctionWithStream } from "@tests/mockUtils";
import { OpikApiError } from "@/rest_api/errors";

describe("Dataset Deduplication", () => {
  let client: OpikClient;
  let dataset: Dataset;

  // API method spies
  let createOrUpdateDatasetItemsSpy: MockInstance<
    typeof client.api.datasets.createOrUpdateDatasetItems
  >;
  let deleteDatasetItemsSpy: MockInstance<
    typeof client.api.datasets.deleteDatasetItems
  >;
  let streamDatasetItemsSpy: MockInstance<
    typeof client.api.datasets.streamDatasetItems
  >;
  let flushSpy: MockInstance<typeof client.datasetBatchQueue.flush>;

  beforeEach(() => {
    // Create a real client with mock API configuration
    client = new OpikClient({
      apiKey: "test-api-key",
      projectName: "test-project-id",
    });

    // Create a test dataset
    dataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "Test Dataset",
        description: "Dataset for deduplication testing",
      },
      client
    );

    // Set up API method spies
    createOrUpdateDatasetItemsSpy = vi
      .spyOn(client.api.datasets, "createOrUpdateDatasetItems")
      .mockResolvedValue(undefined);

    deleteDatasetItemsSpy = vi
      .spyOn(client.api.datasets, "deleteDatasetItems")
      .mockResolvedValue(undefined);

    streamDatasetItemsSpy = vi
      .spyOn(client.api.datasets, "streamDatasetItems")
      .mockImplementation(() => mockAPIFunctionWithStream("[]"));

    flushSpy = vi
      .spyOn(client.datasetBatchQueue, "flush")
      .mockResolvedValue(undefined);
  });

  it("should deduplicate items before insertion", async () => {
    // Create items with duplicate content
    const item1 = { id: "item1", content: "test content" };
    const item2 = { id: "item2", content: "test content" }; // Same content as item1
    const item3 = { id: "item3", content: "different content" };

    // Insert items
    await dataset.insert([item1, item2, item3]);

    // Verify API calls
    expect(flushSpy).toHaveBeenCalledTimes(1);
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // Get the items that were sent to the API
    const sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;

    // Should only have 2 items (item1 and item3), not 3
    expect(sentItems.length).toBe(2);

    // Verify the right items were sent
    const sentIds = sentItems.map((item: DatasetItemWrite) => item.id);
    expect(sentIds).toContain("item1");
    expect(sentIds).not.toContain("item2"); // item2 should be deduplicated
    expect(sentIds).toContain("item3");
  });

  it("should maintain hash tracking when deleting items", async () => {
    // Setup mock getDatasetItems to return existing items
    const existingItems: DatasetItemWrite[] = [
      {
        id: "item1",
        data: { content: "test content" },
        source: "sdk",
      },
      {
        id: "item2",
        data: { content: "different content" },
        source: "sdk",
      },
    ];

    // Format as NDJSON (one JSON object per line)
    const ndjsonData = existingItems
      .map((item) => JSON.stringify(item))
      .join("\n");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(ndjsonData)
    );

    // Sync hashes to initialize the deduplication state
    await dataset.syncHashes();

    // Delete item1
    await dataset.delete(["item1"]);

    // Verify delete API call
    expect(deleteDatasetItemsSpy).toHaveBeenCalledTimes(1);
    expect(deleteDatasetItemsSpy).toHaveBeenCalledWith({
      itemIds: ["item1"],
      batchGroupId: expect.any(String),
    });

    // Update mock to reflect the state after deletion (only item2 remains)
    const remainingItems = existingItems.filter((item) => item.id !== "item1");
    const remainingNdjsonData = remainingItems
      .map((item) => JSON.stringify(item))
      .join("\n");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(remainingNdjsonData)
    );

    // Reset createOrUpdateDatasetItemsSpy to check just the new call
    createOrUpdateDatasetItemsSpy.mockClear();

    // Try to insert an item with the same content as the deleted item1
    const newItem = { id: "item3", content: "test content" };
    await dataset.insert([newItem]);

    // Verify that the new item was sent to the API
    // This proves the hash for the deleted item was removed
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);

    const sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;
    expect(sentItems.length).toBe(1);
    expect(sentItems[0].id).toBe("item3");
  });

  it("should handle multiple duplicate items in the same batch", async () => {
    // Create multiple items with the same content
    const items = [
      { id: "item1", content: "duplicate content" },
      { id: "item2", content: "duplicate content" }, // Same as item1
      { id: "item3", content: "duplicate content" }, // Same as item1
      { id: "item4", content: "unique content" },
    ];

    // Insert all items
    await dataset.insert(items);

    // Verify API calls
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // Get the items that were sent to the API
    const sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;

    // Should only have 2 items (one duplicate and one unique), not 4
    expect(sentItems.length).toBe(2);

    // Verify one of the duplicates and the unique item were sent
    const sentIds = sentItems.map((item: DatasetItemWrite) => item.id);

    // One of the duplicates should be present (the first one encountered)
    expect(sentIds).toContain("item1");

    // The other duplicates should be filtered out
    expect(sentIds).not.toContain("item2");
    expect(sentIds).not.toContain("item3");

    // The unique item should be present
    expect(sentIds).toContain("item4");
  });

  it("should deduplicate against existing backend data", async () => {
    // Setup existing items in the backend
    const existingItems = [
      {
        id: "backend-item-1",
        data: { content: "existing backend content" },
        source: "sdk",
      },
      {
        id: "backend-item-2",
        data: { content: "another backend content" },
        source: "sdk",
      },
    ];

    // Format as NDJSON (one JSON object per line)
    const ndjsonData = existingItems
      .map((item) => JSON.stringify(item))
      .join("\n");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(ndjsonData)
    );

    // Try to insert new items, one of which has the same content as backend item
    const newItems = [
      { id: "new-item-1", content: "existing backend content" }, // Duplicate of backend item
      { id: "new-item-2", content: "completely new content" }, // Unique content
    ];

    await dataset.insert(newItems);

    // Verify sync was called
    expect(streamDatasetItemsSpy).toHaveBeenCalledTimes(1);
    expect(streamDatasetItemsSpy).toHaveBeenCalledWith({
      datasetName: "Test Dataset",
      lastRetrievedId: undefined,
      steamLimit: 2000,
    });

    // Verify API calls
    expect(flushSpy).toHaveBeenCalledTimes(1);
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // Get the items that were sent to the API
    const sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;

    // Should only have 1 item (the unique one), not 2
    expect(sentItems.length).toBe(1);

    // Verify only the unique item was sent
    const sentIds = sentItems.map((item: DatasetItemWrite) => item.id);
    expect(sentIds).not.toContain("new-item-1"); // Duplicate should be filtered out
    expect(sentIds).toContain("new-item-2"); // Unique item should be present
  });

  it("should handle non-existent dataset gracefully", async () => {
    // Mock API to return 404 for non-existent dataset
    const apiError = new OpikApiError({
      message: "Dataset not found",
      statusCode: 404,
    });

    streamDatasetItemsSpy.mockImplementation(() => {
      throw apiError;
    });

    // Try to insert items into non-existent dataset
    const newItems = [
      { id: "item1", content: "test content" },
      { id: "item2", content: "another content" },
    ];

    // Should not throw error
    await expect(dataset.insert(newItems)).resolves.not.toThrow();

    // Verify sync was attempted
    expect(streamDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // Verify API calls still proceeded
    expect(flushSpy).toHaveBeenCalledTimes(1);
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // All items should be sent since no backend data to deduplicate against
    const sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;
    expect(sentItems.length).toBe(2);

    const sentIds = sentItems.map((item: DatasetItemWrite) => item.id);
    expect(sentIds).toContain("item1");
    expect(sentIds).toContain("item2");
  });

  it("should re-throw non-404 API errors during sync", async () => {
    // Mock API to return 500 server error
    const apiError = new OpikApiError({
      message: "Internal server error",
      statusCode: 500,
    });

    streamDatasetItemsSpy.mockImplementation(() => {
      throw apiError;
    });

    const newItems = [{ id: "item1", content: "test content" }];

    // Should re-throw the 500 error
    await expect(dataset.insert(newItems)).rejects.toThrow(apiError);

    // Verify sync was attempted
    expect(streamDatasetItemsSpy).toHaveBeenCalledTimes(1);

    // Verify insert API was never called due to the error
    expect(createOrUpdateDatasetItemsSpy).not.toHaveBeenCalled();
  });

  it("should deduplicate correctly after backend sync", async () => {
    // Setup existing backend data
    const backendItems = [
      {
        id: "backend-1",
        data: { content: "shared content" },
        source: "sdk",
      },
    ];

    // Format as NDJSON (one JSON object per line)
    const ndjsonData = backendItems
      .map((item) => JSON.stringify(item))
      .join("\n");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(ndjsonData)
    );

    // First insertion - should sync with backend and deduplicate
    const firstBatch = [
      { id: "new-1", content: "shared content" }, // Duplicate of backend
      { id: "new-2", content: "unique content" }, // Unique
    ];

    await dataset.insert(firstBatch);

    // Only unique item should be inserted
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);
    let sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;
    expect(sentItems.length).toBe(1);
    expect(sentItems[0].id).toBe("new-2");

    // Reset spy for second insertion
    createOrUpdateDatasetItemsSpy.mockClear();

    // Update mock to include the newly inserted item
    const updatedBackendItems = [
      ...backendItems,
      {
        id: "new-2",
        data: { content: "unique content" },
        source: "sdk",
      },
    ];

    // Format as NDJSON (one JSON object per line)
    const updatedNdjsonData = updatedBackendItems
      .map((item) => JSON.stringify(item))
      .join("\n");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(updatedNdjsonData)
    );

    // Second insertion - should sync again and deduplicate against updated backend
    const secondBatch = [
      { id: "new-3", content: "unique content" }, // Now duplicate of previously inserted
      { id: "new-4", content: "another unique" }, // Unique
    ];

    await dataset.insert(secondBatch);

    // Only the truly unique item should be inserted
    expect(createOrUpdateDatasetItemsSpy).toHaveBeenCalledTimes(1);
    sentItems = createOrUpdateDatasetItemsSpy.mock.calls[0][0].items;
    expect(sentItems.length).toBe(1);
    expect(sentItems[0].id).toBe("new-4");
  });
});
