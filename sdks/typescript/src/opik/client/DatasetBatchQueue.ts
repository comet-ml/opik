import { DatasetWrite } from "@/rest_api/api/resources/datasets/client/requests/DatasetWrite";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

/**
 * Batch queue for dataset operations that allows efficient batching of dataset CRUD operations.
 * Extends the generic BatchQueue to provide specific implementations for dataset resources.
 */
export class DatasetBatchQueue extends BatchQueue<DatasetWrite> {
  /**
   * Creates a new DatasetBatchQueue instance.
   *
   * @param api The OpikApiClient instance used to communicate with the API
   * @param delay Optional delay in milliseconds before flushing the queue (defaults to 300ms)
   */
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "DatasetBatchQueue",
    });
  }

  /**
   * Gets the ID of a dataset entity.
   *
   * @param entity The dataset entity
   * @returns The ID of the dataset
   */
  protected getId(entity: DatasetWrite): string {
    return entity.id || "";
  }

  /**
   * Creates multiple dataset entities in a batch.
   *
   * @param datasets The array of datasets to create
   */
  protected async createEntities(datasets: DatasetWrite[]): Promise<void> {
    // Create datasets one by one since the API doesn't support batch creation
    for (const dataset of datasets) {
      await this.api.datasets.createDataset(dataset, this.api.requestOptions);
    }
  }

  /**
   * Retrieves a dataset by its ID.
   *
   * @param id The ID of the dataset to retrieve
   * @returns The retrieved dataset or undefined if not found
   */
  protected async getEntity(id: string) {
    try {
      const response = await this.api.datasets.getDatasetById(
        id,
        this.api.requestOptions
      );
      return response;
    } catch {
      // Return undefined if dataset not found or another error occurs
      return undefined;
    }
  }

  /**
   * Updates a dataset by its ID with the provided updates.
   *
   * @param id The ID of the dataset to update
   * @param updates Partial dataset properties to update
   */
  protected async updateEntity(
    id: string,
    updates: Partial<DatasetWrite>
  ): Promise<void> {
    await this.api.datasets.updateDataset(
      id,
      {
        name: updates.name || "",
        visibility: updates.visibility,
        description: updates.description,
      },
      this.api.requestOptions
    );
  }

  /**
   * Deletes multiple datasets by their IDs.
   *
   * @param ids Array of dataset IDs to delete
   */
  protected async deleteEntities(ids: string[]): Promise<void> {
    await this.api.datasets.deleteDatasetsBatch(
      {
        ids,
      },
      this.api.requestOptions
    );
  }
}
