import { describe, it, expect, vi, beforeEach, MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetItemWrite } from "@/rest_api/api";
import { mockAPIFunctionWithStream } from "@tests/mockUtils";

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

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(JSON.stringify(existingItems))
    );

    // Sync hashes to initialize the deduplication state
    await dataset.syncHashes();

    // Delete item1
    await dataset.delete(["item1"]);

    // Verify delete API call
    expect(deleteDatasetItemsSpy).toHaveBeenCalledTimes(1);
    expect(deleteDatasetItemsSpy).toHaveBeenCalledWith({
      itemIds: ["item1"],
    });

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
});
