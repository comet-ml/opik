/**
 * Dataset class for managing datasets and their items.
 * Provides methods for creating, retrieving, and managing datasets and their items.
 */
import { generateId } from "@/utils/generateId";
import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { OpikClient } from "@/client/Client";
import { DatasetItemWrite } from "@/rest_api/api";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import { DatasetItemMissingIdError } from "@/errors";
import {
  JsonItemNotObjectError,
  JsonNotArrayError,
  JsonParseError,
} from "@/errors/common/errors";

export interface DatasetData {
  name: string;
  description?: string;
  id?: string;
}

export class Dataset {
  public readonly id: string;
  public readonly name: string;
  public readonly description?: string;

  // deduplication related
  private idToHash: Map<string, string> = new Map();
  private hashes: Set<string> = new Set();

  /**
   * Configuration object for creating a new Dataset instance.
   * This should not be created directly, use static factory methods instead.
   */
  constructor(
    { name, description, id }: DatasetData,
    private opik: OpikClient
  ) {
    this.id = id || generateId();
    this.name = name;
    this.description = description;
  }

  /**
   * Insert new items into the dataset.
   *
   * @param items List of objects to add to the dataset
   */
  public async insert(items: DatasetItemData[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    await this.opik.datasetBatchQueue.flush();

    const reqItems = await this.getDeduplicatedItems(items);

    await this.opik.api.datasets.createOrUpdateDatasetItems({
      datasetId: this.id,
      items: reqItems,
    });
  }

  /**
   * Update existing items in the dataset.
   * You need to provide the full item object as it will override what has been supplied previously.
   *
   * @param items List of objects to update in the dataset
   */
  public async update(items: DatasetItemData[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    // Validate that all items have an ID
    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (!item.id) {
        throw new DatasetItemMissingIdError(i);
      }
    }

    // The API uses the same endpoint for create and update
    // The presence of an ID determines if it's an update
    await this.insert(items);
  }

  /**
   * Delete items from the dataset.
   *
   * @param itemIds List of item ids to delete
   */
  public async delete(itemIds: string[]): Promise<void> {
    if (!itemIds || itemIds.length === 0) {
      logger.info("No item IDs provided for deletion");
      return;
    }

    const batches = splitIntoBatches(itemIds, { maxBatchSize: 100 });

    for await (const batch of batches) {
      console.debug(`Deleting dataset items batch: ${batch}`);
      await this.opik.api.datasets.deleteDatasetItems({
        itemIds: batch,
      });

      // Update local cache
      for (const itemId of batch) {
        if (this.idToHash.has(itemId)) {
          const hash = this.idToHash.get(itemId)!;
          this.hashes.delete(hash);
          this.idToHash.delete(itemId);
        }
      }
    }
  }

  /**
   * Delete all items from the dataset.
   */
  public async clear(): Promise<void> {
    // Get all items from the dataset
    const items = await this.getItems();

    // Extract item IDs
    const itemIds = items.map((item) => item.id).filter(Boolean) as string[];

    // If there are no items, just return
    if (itemIds.length === 0) {
      return;
    }

    // Delete all items
    await this.delete(itemIds);
  }

  /**
   * Retrieve a fixed number of dataset items.
   *
   * @param nbSamples The number of samples to retrieve. If not set - all items are returned
   * @param lastRetrievedId Optional ID of the last retrieved item for pagination
   * @returns A list of objects representing the dataset items
   */
  public async getItems(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<DatasetItemData[]> {
    const datasetItems = await this.getItemsAsDataclasses(
      nbSamples,
      lastRetrievedId
    );

    return datasetItems.map((item) => item.getContent(true));
  }

  private async getItemsAsDataclasses(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<DatasetItem[]> {
    // Prepare the stream request parameters
    const streamLimit = nbSamples ? Math.min(nbSamples, 2000) : 2000; // API max is 2000

    const streamResponse = await this.opik.api.datasets.streamDatasetItems({
      datasetName: this.name,
      lastRetrievedId, // Include this for pagination if provided
      steamLimit: streamLimit, // Properly set the stream limit
    });

    const rawItems = await parseNdjsonStreamToArray<DatasetItemWrite>(
      streamResponse,
      nbSamples
    );

    return rawItems.map((item) => DatasetItem.fromApiModel(item));
  }

  /**
   * Insert items from a JSON string array into the dataset.
   *
   * @param jsonArray JSON string of format: "[{...}, {...}, {...}]" where every object is transformed into a dataset item
   * @param keysMapping Optional dictionary that maps JSON keys to dataset item field names (e.g., {'Expected output': 'expected_output'})
   * @param ignoreKeys Optional array of keys that should be ignored when constructing dataset items
   */
  public async insertFromJson(
    jsonArray: string,
    keysMapping: Record<string, string> = {},
    ignoreKeys: string[] = []
  ): Promise<void> {
    // Parse the JSON string to an array of objects
    let parsedItems: unknown;

    try {
      parsedItems = JSON.parse(jsonArray);
    } catch (error) {
      throw new JsonParseError(error);
    }

    // Validate that the parsed data is an array
    if (!Array.isArray(parsedItems)) {
      throw new JsonNotArrayError(typeof parsedItems);
    }

    // Handle empty array case
    if (parsedItems.length === 0) {
      return;
    }

    // Validate all items are objects
    for (let i = 0; i < parsedItems.length; i++) {
      const item = parsedItems[i];
      if (typeof item !== "object" || item === null) {
        throw new JsonItemNotObjectError(i, typeof item);
      }
    }

    // Transform the items based on keysMapping and ignoreKeys
    const transformedItems = parsedItems.map((item) => {
      // Type assertion after validation
      const typedItem = item as Record<string, unknown>;
      const transformedItem: Record<string, unknown> = {};

      // Process each key in the item
      for (const [key, value] of Object.entries(typedItem)) {
        // Skip keys that should be ignored
        if (ignoreKeys.includes(key)) {
          continue;
        }

        // Map keys if a mapping exists, otherwise use the original key
        const mappedKey = keysMapping[key] || key;
        transformedItem[mappedKey] = value;
      }

      return transformedItem as DatasetItemData;
    });

    // Insert the transformed items into the dataset
    await this.insert(transformedItems);
  }

  /**
   * Convert the dataset to a JSON string.
   *
   * @param keysMapping Optional dictionary that maps dataset item field names to output JSON keys
   * @returns A JSON string representation of all items in the dataset
   */
  public async toJson(
    keysMapping: Record<string, string> = {}
  ): Promise<string> {
    // Get all items from the dataset
    const items = await this.getItems();

    // Apply any key mappings if provided
    if (Object.keys(keysMapping).length > 0) {
      // Transform each item using the keysMapping
      for (const item of items) {
        for (const [key, value] of Object.entries(keysMapping)) {
          if (key in item) {
            const content = item[key];
            delete item[key];
            item[value] = content;
          }
        }
      }
    }

    // Convert to JSON string
    return JSON.stringify(items);
  }

  /**
   * Retrieves all items from the dataset, deduplicates them, and returns them.
   *
   * @returns A list of deduplicated dataset items
   */
  private async getDeduplicatedItems(
    items: DatasetItemData[]
  ): Promise<DatasetItemWrite[]> {
    const deduplicatedItems: DatasetItemWrite[] = [];

    for await (const item of items) {
      const datasetItem = new DatasetItem(item);
      const contentHash = await datasetItem.contentHash();

      if (this.hashes.has(contentHash)) {
        console.debug(
          `Duplicate item found with hash: ${contentHash} - ignored the event`
        );
        continue;
      }

      deduplicatedItems.push(datasetItem.toApiModel());
      this.hashes.add(contentHash);
      this.idToHash.set(datasetItem.id, contentHash);
    }

    return deduplicatedItems;
  }

  public async syncHashes(): Promise<void> {
    console.debug("Start hash sync in dataset");
    const allItems = await this.getItemsAsDataclasses();

    this.idToHash.clear();
    this.hashes.clear();

    for (const item of allItems) {
      const itemHash = await item.contentHash();
      this.idToHash.set(item.id, itemHash);
      this.hashes.add(itemHash);
    }
  }
}
