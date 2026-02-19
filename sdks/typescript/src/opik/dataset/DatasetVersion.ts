import { DatasetItemData } from "./DatasetItem";
import { getDatasetItems } from "./getDatasetItems";
import { OpikClient } from "@/client/Client";
import { DatasetVersionPublic } from "@/rest_api/api";
import stringify from "fast-json-stable-stringify";

/**
 * A read-only view of a specific dataset version.
 * Provides access to dataset items as they existed at a particular version.
 *
 * @template T The type of custom data stored in dataset items
 */
export class DatasetVersion<T extends DatasetItemData = DatasetItemData> {
  public readonly datasetName: string;
  public readonly datasetId: string;
  private readonly versionInfo: DatasetVersionPublic;
  private readonly opik: OpikClient;

  constructor(
    datasetName: string,
    datasetId: string,
    versionInfo: DatasetVersionPublic,
    opik: OpikClient
  ) {
    this.datasetName = datasetName;
    this.datasetId = datasetId;
    this.versionInfo = versionInfo;
    this.opik = opik;
  }

  /**
   * Alias for datasetName (compatibility with Dataset interface).
   */
  get name(): string {
    return this.datasetName;
  }

  /**
   * Alias for datasetId (compatibility with Dataset interface).
   */
  get id(): string {
    return this.datasetId;
  }

  /**
   * Gets the version ID.
   */
  get versionId(): string | undefined {
    return this.versionInfo.id;
  }

  /**
   * Gets the version hash.
   */
  get versionHash(): string | undefined {
    return this.versionInfo.versionHash;
  }

  /**
   * Gets the version name (e.g., "v1", "v2").
   */
  get versionName(): string | undefined {
    return this.versionInfo.versionName;
  }

  /**
   * Gets the tags associated with this version.
   */
  get tags(): string[] | undefined {
    return this.versionInfo.tags;
  }

  /**
   * Indicates whether this is the latest version.
   */
  get isLatest(): boolean | undefined {
    return this.versionInfo.isLatest;
  }

  /**
   * Gets the total number of items in this version.
   */
  get itemsTotal(): number | undefined {
    return this.versionInfo.itemsTotal;
  }

  /**
   * Gets the number of items added since the previous version.
   */
  get itemsAdded(): number | undefined {
    return this.versionInfo.itemsAdded;
  }

  /**
   * Gets the number of items modified since the previous version.
   */
  get itemsModified(): number | undefined {
    return this.versionInfo.itemsModified;
  }

  /**
   * Gets the number of items deleted since the previous version.
   */
  get itemsDeleted(): number | undefined {
    return this.versionInfo.itemsDeleted;
  }

  /**
   * Gets the change description for this version.
   */
  get changeDescription(): string | undefined {
    return this.versionInfo.changeDescription;
  }

  /**
   * Gets the creation timestamp.
   */
  get createdAt(): Date | undefined {
    return this.versionInfo.createdAt;
  }

  /**
   * Gets the creator of this version.
   */
  get createdBy(): string | undefined {
    return this.versionInfo.createdBy;
  }

  /**
   * Returns the full version info object.
   */
  getVersionInfo(): DatasetVersionPublic {
    return this.versionInfo;
  }

  /**
   * Retrieve a fixed number of dataset items from this version.
   *
   * @param nbSamples The number of samples to retrieve. If not set - all items are returned
   * @param lastRetrievedId Optional ID of the last retrieved item for pagination
   * @returns A list of objects representing the dataset items
   */
  public async getItems(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<(T & { id: string })[]> {
    const datasetItems = await getDatasetItems<T>(this.opik, {
      datasetName: this.datasetName,
      datasetVersion: this.versionInfo.versionHash,
      nbSamples,
      lastRetrievedId,
    });
    return datasetItems.map((item) => item.getContent(true));
  }

  /**
   * Convert the dataset version items to a JSON string.
   *
   * @param keysMapping Optional dictionary that maps dataset item field names to output JSON keys
   * @returns A JSON string representation of all items in this version
   */
  public async toJson(
    keysMapping: Record<string, string> = {}
  ): Promise<string> {
    const items = await this.getItems();

    const mappedItems: Record<string, unknown>[] = items.map((item) => {
      const itemCopy = { ...item } as Record<string, unknown>;

      for (const [key, value] of Object.entries(keysMapping)) {
        if (key in itemCopy) {
          const content = itemCopy[key];
          delete itemCopy[key];
          itemCopy[value] = content;
        }
      }

      return itemCopy;
    });

    return stringify(mappedItems);
  }
}
