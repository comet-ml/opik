import { generateId } from "@/utils/generateId";
import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { OpikClient } from "@/client/Client";
import { DatasetItemPublic, DatasetItemWrite } from "@/rest_api/api";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import { DatasetItemMissingIdError } from "@/errors";
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

export interface DatasetSplitResult<
  T extends DatasetItemData = DatasetItemData
> {
  train: (T & { id: string })[];
  validation: (T & { id: string })[];
}

export interface DatasetSplitOptions<
  T extends DatasetItemData = DatasetItemData
> {
  validationDataset?: Dataset<T>;
  validationIds?: string[];
  splitField?: string;
  trainLabel?: string;
  validationLabel?: string;
  validationRatio?: number;
  seed?: number;
  limit?: number;
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

    try {
      let totalInserted = 0;
      for (const batch of batches) {
        await this.opik.api.datasets.createOrUpdateDatasetItems({
          datasetId: this.id,
          items: batch,
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

    for await (const batch of batches) {
      logger.debug("Deleting dataset items batch", {
        batchSize: batch.length,
        datasetId: this.id,
      });
      await this.opik.api.datasets.deleteDatasetItems({
        itemIds: batch,
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

  public async getSplit(
    options: DatasetSplitOptions<T> = {}
  ): Promise<DatasetSplitResult<T>> {
    const {
      validationDataset,
      validationIds,
      splitField,
      trainLabel = "train",
      validationLabel = "validation",
      validationRatio,
      seed,
      limit,
    } = options;

    const strategiesSelected = [
      validationDataset,
      validationIds && validationIds.length > 0,
      splitField,
      typeof validationRatio === "number",
    ].filter(Boolean).length;

    if (strategiesSelected > 1) {
      throw new Error(
        "Only one validation split strategy can be provided at a time."
      );
    }

    const trainItems = await this.getItems(limit);
    let validationItems: (T & { id: string })[] = [];

    if (validationDataset) {
      validationItems = await validationDataset.getItems(limit);
      return { train: trainItems, validation: validationItems };
    }

    if (validationIds && validationIds.length > 0) {
      const idSet = new Set(validationIds);
      validationItems = trainItems.filter(
        (item) => item.id && idSet.has(item.id)
      );
      const filteredTrain = trainItems.filter(
        (item) => !item.id || !idSet.has(item.id)
      );
      return { train: filteredTrain, validation: validationItems };
    }

    if (splitField) {
      const trainSplit: (T & { id: string })[] = [];
      const validationSplit: (T & { id: string })[] = [];

      for (const item of trainItems) {
        const value =
          (item as Record<string, unknown>)[splitField] ??
          Dataset.extractSplitValue(item as Record<string, unknown>, splitField);

        if (value === validationLabel) {
          validationSplit.push(item);
        } else if (value === trainLabel || value === undefined) {
          trainSplit.push(item);
        } else {
          trainSplit.push(item);
        }
      }

      return { train: trainSplit, validation: validationSplit };
    }

    if (typeof validationRatio === "number") {
      if (!(validationRatio > 0 && validationRatio < 1)) {
        throw new Error(
          "validationRatio must be between 0 and 1 (exclusive)."
        );
      }

      if (trainItems.length <= 1) {
        return { train: trainItems, validation: [] };
      }

      const rng = createSeededRandom(seed ?? 0);
      const shuffled = [...trainItems];
      for (let i = shuffled.length - 1; i > 0; i -= 1) {
        const j = Math.floor(rng() * (i + 1));
        [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
      }

      let validationCount = Math.round(shuffled.length * validationRatio);
      if (validationCount <= 0) {
        validationCount = 1;
      } else if (validationCount >= shuffled.length) {
        validationCount = shuffled.length - 1;
      }

      const validationSplit = shuffled.slice(0, validationCount);
      const trainSplit = shuffled.slice(validationCount);
      return { train: trainSplit, validation: validationSplit };
    }

    return { train: trainItems, validation: [] };
  }

  private async getItemsAsDataclasses(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<DatasetItem<T>[]> {
    const streamLimit = nbSamples ? Math.min(nbSamples, 2000) : 2000; // API max is 2000

    const streamResponse = await this.opik.api.datasets.streamDatasetItems({
      datasetName: this.name,
      lastRetrievedId,
      steamLimit: streamLimit,
    });

    const rawItems = await parseNdjsonStreamToArray<DatasetItemPublic>(
      streamResponse,
      serialization.DatasetItemPublic,
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
  private static extractSplitValue(
    item: Record<string, unknown>,
    key: string
  ): unknown {
    const metadata = (item as { metadata?: unknown }).metadata;
    if (metadata && typeof metadata === "object") {
      return (metadata as Record<string, unknown>)[key];
    }
    return undefined;
  }

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
}

function createSeededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), state | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
