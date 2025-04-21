import { loadConfig } from "@/config/Config";
import { OpikApiClient } from "@/rest_api";
import { stringifyWithSortedKeys } from "@/dataset/utils";
import { createHash } from "crypto";
import { generateId } from "@/utils/generateId";
import { DatasetItemPublic, DatasetItemWriteSource } from "@/rest_api/api";

const DATASET_ITEMS_MAX_BATCH_SIZE = 1000;
const MAX_CONCURRENT_REQUESTS = 5;

export class Dataset {
  id: string;
  name: string;
  description?: string;
  private api: OpikApiClient;
  private itemsData?: Record<string, unknown>[];
  private hashes: string[] = [];
  private idToHash: Record<string, string> = {};

  constructor(
    id: string,
    name: string,
    description?: string,
    api?: OpikApiClient,
  ) {
    this.id = id;
    this.name = name;
    this.description = description;

    if (api) {
      this.api = api;
    } else {
      const config = loadConfig({});
      this.api = new OpikApiClient({
        apiKey: config.apiKey,
        environment: config.apiUrl,
        workspaceName: config.workspaceName,
      });
    }
  }

  public async insert(items: Record<string, unknown>[]) {
    // Remove items that are already part of the dataset so avoid duplicates
    items = items.filter((item) => {
      const itemContentString = stringifyWithSortedKeys(item);
      const hash = createHash("sha256");
      hash.update(itemContentString);

      if (!this.hashes.includes(hash.digest("hex"))) {
        return true;
      } else {
        return false;
      }
    });

    const promises: Promise<void>[] = [];
    console.log(`Inserting ${items.length} items into dataset "${this.name}"`);
    for (let i = 0; i < items.length; i += DATASET_ITEMS_MAX_BATCH_SIZE) {
      const batch = items.slice(i, i + DATASET_ITEMS_MAX_BATCH_SIZE);
      const datasetItemsBatch = batch.map((item) => {
        return {
          id: generateId() as string,
          data: item,
          source: DatasetItemWriteSource["Sdk"],
        };
      });
      const promise = this.api.datasets.createOrUpdateDatasetItems({
        items: datasetItemsBatch,
        datasetId: this.id,
      });

      // Add the items from the batch to the hashes map
      datasetItemsBatch.map((item) => {
        const itemContentString = stringifyWithSortedKeys(item);
        const hash = createHash("sha256");
        hash.update(itemContentString);
        const hashDigest = hash.digest("hex");
        
        this.idToHash[item.id] = hashDigest;
        this.hashes.push(hashDigest);
      });

      promises.push(promise);

      // If the number of promises reaches the max concurrent limit, wait for them to resolve
      if (promises.length === MAX_CONCURRENT_REQUESTS) {
        await Promise.all(promises);
        promises.length = 0; // Clear the array
      }
    }
  }

  public async __internal_api__sync_hashes__() {
    const items = await this.__getFullItems();

    this.hashes = [];
    this.idToHash = {};

    items.map((item) => {
      const itemContentString = stringifyWithSortedKeys(item.data);
      const hash = createHash("sha256");
      hash.update(itemContentString);
      const hashDigest = hash.digest("hex");

      this.hashes.push(hashDigest);
      this.idToHash[item.id || ""] = hashDigest;
    });
  }

  public async delete(itemIds: string[]) {
    const promises: Promise<void>[] = [];
    for (let i = 0; i < itemIds.length; i += DATASET_ITEMS_MAX_BATCH_SIZE) {
      const batch = itemIds.slice(i, i + DATASET_ITEMS_MAX_BATCH_SIZE);

      const promise = this.api.datasets.deleteDatasetItems({
        itemIds: batch,
      });

      // Remove the hashes and ids from the dataset
      batch.map((itemId) => {
        delete this.idToHash[itemId];
        this.hashes.splice(this.hashes.indexOf(this.idToHash[itemId]), 1);
      });

      promises.push(promise);

      // If the number of promises reaches the max concurrent limit, wait for them to resolve
      if (promises.length === MAX_CONCURRENT_REQUESTS) {
        await Promise.all(promises);
        promises.length = 0; // Clear the array
      }
    }

    // Wait for any remaining promises
    if (promises.length > 0) {
      await Promise.all(promises);
    }
  }

  public async clear() {
    const items = await this.__getFullItems();
    await this.delete(items.map((item) => item.id || ""));
  }

  async __getFullItems(nbSamples?: number) {
    let page = 1;
    let allItems: DatasetItemPublic[] = [];

    while (true) {
      const response = await this.api.datasets.getDatasetItems(this.id, {
        size: DATASET_ITEMS_MAX_BATCH_SIZE,
        page: page,
      });
      const items = response?.content || [];
      allItems = allItems.concat(items);

      if (
        items.length < DATASET_ITEMS_MAX_BATCH_SIZE ||
        (nbSamples && allItems.length >= nbSamples)
      ) {
        break;
      }

      page++;
    }
    if (nbSamples) {
      return allItems.slice(0, nbSamples);  
    } else {
      return allItems;
    }
  }

  public async getItems(nbSamples?: number) {
    const items = await this.__getFullItems(nbSamples);

    return items.map((item) => item.data);
  }
}
