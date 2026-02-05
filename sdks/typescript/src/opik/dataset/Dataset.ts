import { generateId } from "@/utils/generateId";
import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { DatasetVersion } from "./DatasetVersion";
import { OpikClient } from "@/client/Client";
import {
  DatasetItemPublic,
  DatasetItemWrite,
  DatasetVersionPublic,
} from "@/rest_api/api";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import {
  DatasetItemMissingIdError,
  DatasetVersionNotFoundError,
} from "@/errors";
import {
  JsonItemNotObjectError,
  JsonNotArrayError,
  JsonParseError,
} from "@/errors/common/errors";
import { serialization } from "@/rest_api";
import { OpikApiError } from "@/rest_api/errors";
import stringify from "fast-json-stable-stringify";

export interface DatasetData {
  name: string;
  description?: string;
  id?: string;
}

export class Dataset<T extends DatasetItemData = DatasetItemData> {
  public readonly id: string;
  public readonly name: string;
  public readonly description?: string;

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
  public async insert(items: T[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    await this.opik.datasetBatchQueue.flush();

    await this.syncHashes();

    const reqItems = await this.getDeduplicatedItems(items);

    const batches = splitIntoBatches(reqItems, { maxBatchSize: 1000 });

    const batchGroupId = generateId();

    try {
      let totalInserted = 0;
      for (const batch of batches) {
        await this.opik.api.datasets.createOrUpdateDatasetItems({
          datasetId: this.id,
          items: batch,
          batchGroupId,
        });
        totalInserted += batch.length;
        logger.info(
          `Inserted ${Math.min(totalInserted, reqItems.length)} of ${reqItems.length} items into dataset ${this.id}`
        );
      }
    } catch (error) {
      logger.error(
        `Error inserting items into dataset: ${error instanceof Error ? error.message : String(error)}`
      );
      throw error;
    }
  }

  /**
   * Update existing items in the dataset.
   * You need to provide the full item object as it will override what has been supplied previously.
   *
   * @param items List of objects to update in the dataset
   */
  public async update(items: T[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (!item.id) {
        throw new DatasetItemMissingIdError(i);
      }
    }

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

    const batchGroupId = generateId();

    for await (const batch of batches) {
      logger.debug("Deleting dataset items batch", {
        batchSize: batch.length,
        datasetId: this.id,
      });
      await this.opik.api.datasets.deleteDatasetItems({
        itemIds: batch,
        batchGroupId,
      });

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
    const items = await this.getItems();
    const itemIds = items.map((item) => item.id).filter(Boolean) as string[];

    if (itemIds.length === 0) {
      return;
    }

    await this.delete(itemIds);
  }

  /**
   * Retrieve a fixed number of dataset items.
   *
   * @param nbSamples The number of samples to retrieve. If not set - all items are returned
   * @param lastRetrievedId Optional ID of the last retrieved item for pagination
   * @returns A list of objects representing the dataset items
   */
  public async getItems(nbSamples?: number, lastRetrievedId?: string) {
    const datasetItems = await this.getItemsAsDataclasses(
      nbSamples,
      lastRetrievedId
    );

    return datasetItems.map((item) => item.getContent(true));
  }

  private async getItemsAsDataclasses(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<DatasetItem<T>[]> {
    const MAX_STREAM_LIMIT = 2000;
    const allItems: DatasetItem<T>[] = [];
    let remaining = nbSamples;
    let currentLastId = lastRetrievedId;

    while (true) {
      const streamLimit =
        remaining !== undefined
          ? Math.min(remaining, MAX_STREAM_LIMIT)
          : MAX_STREAM_LIMIT;

      const streamResponse = await this.opik.api.datasets.streamDatasetItems({
        datasetName: this.name,
        lastRetrievedId: currentLastId,
        steamLimit: streamLimit,
      });

      const rawItems = await parseNdjsonStreamToArray<DatasetItemPublic>(
        streamResponse,
        serialization.DatasetItemPublic,
        streamLimit
      );

      if (rawItems.length === 0) {
        break;
      }

      const items = rawItems.map((item) => DatasetItem.fromApiModel<T>(item));
      allItems.push(...items);

      currentLastId = rawItems[rawItems.length - 1].id;

      if (remaining !== undefined) {
        remaining -= rawItems.length;
        if (remaining <= 0) {
          break;
        }
      }

      // If we got fewer items than requested, we've reached the end
      if (rawItems.length < streamLimit) {
        break;
      }
    }

    return allItems;
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
    let parsedItems: unknown;

    try {
      parsedItems = JSON.parse(jsonArray);
    } catch (error) {
      throw new JsonParseError(error);
    }

    if (!Array.isArray(parsedItems)) {
      throw new JsonNotArrayError(typeof parsedItems);
    }

    if (parsedItems.length === 0) {
      return;
    }

    for (let i = 0; i < parsedItems.length; i++) {
      const item = parsedItems[i];
      if (typeof item !== "object" || item === null) {
        throw new JsonItemNotObjectError(i, typeof item);
      }
    }

    const transformedItems = parsedItems.map((item) => {
      const typedItem = item as Record<string, unknown>;
      const transformedItem: Record<string, unknown> = {};

      for (const [key, value] of Object.entries(typedItem)) {
        if (ignoreKeys.includes(key)) {
          continue;
        }

        const mappedKey = keysMapping[key] || key;
        transformedItem[mappedKey] = value;
      }

      return transformedItem as T;
    });

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
    const items = await this.getItems();

    const mappedItems: Record<string, unknown>[] = items.map((item) => {
      const itemCopy = { ...item } as Record<string, unknown>;

      if (Object.keys(keysMapping).length > 0) {
        for (const [key, value] of Object.entries(keysMapping)) {
          if (key in itemCopy) {
            const content = itemCopy[key];
            delete itemCopy[key];
            itemCopy[value] = content;
          }
        }
      }

      return itemCopy;
    });

    return stringify(mappedItems);
  }

  /**
   * Retrieves all items from the dataset, deduplicates them, and returns them.
   *
   * @returns A list of deduplicated dataset items
   */
  private async getDeduplicatedItems(items: T[]): Promise<DatasetItemWrite[]> {
    const deduplicatedItems: DatasetItemWrite[] = [];

    for (const item of items) {
      const datasetItem = new DatasetItem<T>(item);
      const contentHash = await datasetItem.contentHash();

      if (this.hashes.has(contentHash)) {
        logger.debug("Duplicate item found - skipping", {
          contentHash,
          datasetId: this.id,
        });
        continue;
      }

      deduplicatedItems.push(datasetItem.toApiModel());
      this.hashes.add(contentHash);
      this.idToHash.set(datasetItem.id, contentHash);
    }

    return deduplicatedItems;
  }

  /**
   * Clears both hash tracking data structures
   */
  private clearHashState(): void {
    this.idToHash.clear();
    this.hashes.clear();
  }

  public async syncHashes(): Promise<void> {
    logger.debug("Syncing dataset hashes with backend", { datasetId: this.id });

    try {
      const allItems = await this.getItemsAsDataclasses();

      this.clearHashState();

      for (const item of allItems) {
        const itemHash = await item.contentHash();
        this.idToHash.set(item.id, itemHash);
        this.hashes.add(itemHash);
      }

      logger.debug("Dataset hash sync completed", {
        datasetId: this.id,
        itemCount: allItems.length,
      });
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        logger.debug("Dataset not found - starting with empty hash state", {
          datasetId: this.id,
        });
        this.clearHashState();
        return;
      }
      throw error;
    }
  }

  /**
   * Get a read-only view of a specific dataset version.
   *
   * @param versionName The version name to retrieve (e.g., "v1", "v2")
   * @returns A DatasetVersion object for the specified version
   * @throws DatasetVersionNotFoundError if the version doesn't exist
   */
  public async getVersionView(versionName: string): Promise<DatasetVersion<T>> {
    const versionInfo = await this.findVersionByName(versionName);

    if (!versionInfo) {
      throw new DatasetVersionNotFoundError(versionName, this.name);
    }

    return new DatasetVersion<T>(this.name, this.id, versionInfo, this.opik);
  }

  /**
   * Get the current (latest) version name.
   *
   * @returns The version name (e.g., "v1") or undefined if no versions exist
   */
  public async getCurrentVersionName(): Promise<string | undefined> {
    const versionInfo = await this.getVersionInfo();
    return versionInfo?.versionName;
  }

  /**
   * Get the current (latest) version info.
   *
   * @returns The DatasetVersionPublic object or undefined if no versions exist
   */
  public async getVersionInfo(): Promise<DatasetVersionPublic | undefined> {
    try {
      const response = await this.opik.api.datasets.listDatasetVersions(
        this.id,
        { page: 1, size: 1 }
      );

      const versions = response.content ?? [];
      if (versions.length === 0) {
        return undefined;
      }

      return versions[0];
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return undefined;
      }
      throw error;
    }
  }

  /**
   * Find a version by its name (e.g., "v1", "v2").
   * Uses paginated search to find the version.
   *
   * @param versionName The version name to find
   * @returns The DatasetVersionPublic or undefined if not found
   */
  private async findVersionByName(
    versionName: string
  ): Promise<DatasetVersionPublic | undefined> {
    const pageSize = 100;
    let page = 1;

    while (true) {
      try {
        const response = await this.opik.api.datasets.listDatasetVersions(
          this.id,
          { page, size: pageSize }
        );

        const versions = response.content ?? [];
        if (versions.length === 0) {
          return undefined;
        }

        const found = versions.find((v) => v.versionName === versionName);
        if (found) {
          return found;
        }

        // If we got fewer results than page size, we've reached the end
        if (versions.length < pageSize) {
          return undefined;
        }

        page++;
      } catch (error) {
        if (error instanceof OpikApiError && error.statusCode === 404) {
          return undefined;
        }
        throw error;
      }
    }
  }
}
