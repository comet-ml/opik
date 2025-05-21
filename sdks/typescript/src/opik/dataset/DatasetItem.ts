import { v4 as uuidv4 } from "uuid";
import { DatasetItemWriteSource } from "@/rest_api/api";
import { JsonNode } from "@/rest_api/api/types/JsonNode";
import { DatasetItemWrite } from "@/rest_api/api/types/DatasetItemWrite";

export type DatasetItemData = JsonNode & {
  id?: string;
};

/**
 * A DatasetItem object representing an item in a dataset.
 * The format is flexible, allowing for additional properties.
 *
 * @template T The type of custom data stored in the dataset item
 */
export class DatasetItem<T extends DatasetItemData = DatasetItemData> {
  /**
   * The unique identifier for this dataset item.
   */
  public readonly id: string;

  /**
   * The ID of the trace associated with this dataset item.
   */
  public readonly traceId?: string;

  /**
   * The ID of the span associated with this dataset item.
   */
  public readonly spanId?: string;

  /**
   * The source of the dataset item. Defaults to SDK.
   */
  public readonly source: DatasetItemWriteSource;

  /**
   * Additional data associated with this dataset item.
   */
  private readonly data: T;

  /**
   * Creates a new DatasetItem.
   *
   * @param params Configuration options for the dataset item
   */
  constructor(
    params: {
      id?: string;
      traceId?: string;
      spanId?: string;
      source?: DatasetItemWriteSource;
    } & T
  ) {
    const { id, traceId, spanId, source, ...rest } = params;

    this.id = id || uuidv4();
    this.traceId = traceId;
    this.spanId = spanId;
    this.source = source || DatasetItemWriteSource.Sdk;
    this.data = { ...rest } as T;
  }

  /**
   * Gets the content of this dataset item as a JSON object.
   *
   * @param includeId Whether to include the ID in the content
   * @returns The content as a JSON object
   */
  public getContent(includeId = false): JsonNode {
    const content: T = { ...this.data };

    if (includeId) {
      content.id = this.id;
    }

    return content;
  }

  /**
   * Creates a new dataset item with updated data.
   * This maintains immutability by returning a new instance.
   *
   * @param newData The new data to merge with the existing data
   * @returns A new DatasetItem instance with the merged data
   */
  public update<U extends DatasetItemData>(newData: U): DatasetItem<T & U> {
    return new DatasetItem<T & U>({
      id: this.id,
      traceId: this.traceId,
      spanId: this.spanId,
      source: this.source,
      ...this.data,
      ...newData,
    });
  }

  /**
   * Converts this DatasetItem to the API model format.
   *
   * @returns A DatasetItemWrite object suitable for API operations
   */
  public toApiModel(): DatasetItemWrite {
    return {
      id: this.id,
      traceId: this.traceId,
      spanId: this.spanId,
      source: this.source,
      data: this.getContent(),
    };
  }

  /**
   * Creates a DatasetItem from an API model.
   *
   * @param model The API model to convert
   * @returns A new DatasetItem instance
   */
  public static fromApiModel<T extends DatasetItemData = DatasetItemData>(
    model: DatasetItemWrite
  ): DatasetItem<T> {
    return new DatasetItem<T>({
      id: model.id,
      traceId: model.traceId,
      spanId: model.spanId,
      source: model.source,
      ...(model.data as T),
    });
  }
}
