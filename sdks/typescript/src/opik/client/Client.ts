import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient, OpikApiError } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrl } from "@/utils/url";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { SpanFeedbackScoresBatchQueue } from "./SpanFeedbackScoresBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";
import { TraceFeedbackScoresBatchQueue } from "./TraceFeedbackScoresBatchQueue";
import { DatasetBatchQueue } from "./DatasetBatchQueue";
import { Dataset, DatasetItemData, DatasetNotFoundError } from "@/dataset";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

export const clients: OpikClient[] = [];

export class OpikClient {
  public api: OpikApiClient;
  public config: OpikConfig;
  public spanBatchQueue: SpanBatchQueue;
  public traceBatchQueue: TraceBatchQueue;
  public spanFeedbackScoresBatchQueue: SpanFeedbackScoresBatchQueue;
  public traceFeedbackScoresBatchQueue: TraceFeedbackScoresBatchQueue;
  public datasetBatchQueue: DatasetBatchQueue;

  private lastProjectNameLogged: string | undefined;

  constructor(explicitConfig?: Partial<OpikConfig>) {
    logger.debug("Initializing OpikClient with config:", explicitConfig);
    this.config = loadConfig(explicitConfig);
    this.api = new OpikApiClient({
      apiKey: this.config.apiKey,
      environment: this.config.apiUrl,
      workspaceName: this.config.workspaceName,
    });

    this.spanBatchQueue = new SpanBatchQueue(this.api);
    this.traceBatchQueue = new TraceBatchQueue(this.api);
    this.spanFeedbackScoresBatchQueue = new SpanFeedbackScoresBatchQueue(
      this.api
    );
    this.traceFeedbackScoresBatchQueue = new TraceFeedbackScoresBatchQueue(
      this.api
    );
    this.datasetBatchQueue = new DatasetBatchQueue(this.api);

    clients.push(this);
  }

  private displayTraceLog = (projectName: string) => {
    if (projectName === this.lastProjectNameLogged || !this.config.apiUrl) {
      return;
    }

    const projectUrl = getProjectUrl({
      apiUrl: this.config.apiUrl,
      projectName,
      workspaceName: this.config.workspaceName,
    });

    logger.info(
      `Started logging traces to the "${projectName}" project at ${createLink(projectUrl)}`
    );

    this.lastProjectNameLogged = projectName;
  };

  public trace = (traceData: TraceData) => {
    logger.debug("Creating new trace with data:", traceData);
    const projectName = traceData.projectName ?? this.config.projectName;
    const trace = new Trace(
      {
        id: generateId(),
        startTime: new Date(),
        ...traceData,
        projectName,
      },
      this
    );

    this.traceBatchQueue.create(trace.data);
    logger.debug("Trace added to the queue with ID:", trace.data.id);
    this.displayTraceLog(projectName);

    return trace;
  };

  /**
   * Retrieves an existing dataset by name
   *
   * @param name The name of the dataset to retrieve
   * @returns A Dataset object associated with the specified name
   * @throws Error if the dataset doesn't exist
   */
  public getDataset = async <T extends DatasetItemData = DatasetItemData>(
    name: string
  ): Promise<Dataset<T>> => {
    logger.debug(`Getting dataset with name "${name}"`);
    try {
      // TODO Requires Batch class update to be able use name instead of id and get it from there
      await this.datasetBatchQueue.flush();

      const response = await this.api.datasets.getDatasetByIdentifier({
        datasetName: name,
      });

      return new Dataset<T>(response, this);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        throw new DatasetNotFoundError(name);
      }
      throw error;
    }
  };

  /**
   * Creates a new dataset with the given name and optional description
   *
   * @param name The name of the dataset
   * @param description Optional description of the dataset
   * @returns The created Dataset object
   */
  public createDataset = async <T extends DatasetItemData = DatasetItemData>(
    name: string,
    description?: string
  ): Promise<Dataset<T>> => {
    logger.debug(`Creating dataset with name "${name}"`);

    const entity = new Dataset<T>({ name, description }, this);

    try {
      this.datasetBatchQueue.create({
        name: entity.name,
        description: entity.description,
        id: entity.id,
      });

      logger.debug("Dataset added to the queue with name:", entity.name);

      return entity;
    } catch (error) {
      logger.error(`Failed to create dataset "${name}"`, { error });
      throw new Error(`Error creating dataset "${name}": ${error}`);
    }
  };

  /**
   * Retrieves an existing dataset by name or creates a new one if it doesn't exist.
   *
   * @param name The name of the dataset
   * @param description Optional description of the dataset (used if created)
   * @returns A promise that resolves to the existing or newly created Dataset object
   */
  public getOrCreateDataset = async <
    T extends DatasetItemData = DatasetItemData,
  >(
    name: string,
    description?: string
  ): Promise<Dataset<T>> => {
    logger.debug(
      `Attempting to retrieve or create dataset with name: "${name}"`
    );

    try {
      return await this.getDataset(name);
    } catch (error) {
      if (error instanceof DatasetNotFoundError) {
        logger.info(
          `Dataset "${name}" not found. Proceeding to create a new one.`
        );
        return this.createDataset(name, description);
      }
      logger.error(`Error retrieving dataset "${name}":`, error);
      throw error;
    }
  };

  /**
   * Returns all datasets up to the specified limit
   *
   * @param maxResults Maximum number of datasets to return (default: 100)
   * @returns List of Dataset objects
   */
  public getDatasets = async <T extends DatasetItemData = DatasetItemData>(
    maxResults: number = 100
  ): Promise<Dataset<T>[]> => {
    logger.debug(`Getting all datasets (limit: ${maxResults})`);

    try {
      // Flush the queue first to ensure all pending datasets are created
      await this.datasetBatchQueue.flush();

      const response = await this.api.datasets.findDatasets({
        size: maxResults,
      });

      const datasets: Dataset<T>[] = [];

      for (const datasetData of response.content || []) {
        datasets.push(new Dataset<T>(datasetData, this));
      }

      logger.info(`Retrieved ${datasets.length} datasets`);
      return datasets;
    } catch (error) {
      logger.error("Failed to retrieve datasets", { error });
      throw new Error("Failed to retrieve datasets");
    }
  };

  /**
   * Deletes a dataset by name
   *
   * @param name The name of the dataset to delete
   */
  public deleteDataset = async (name: string): Promise<void> => {
    logger.debug(`Deleting dataset with name "${name}"`);

    try {
      const dataset = await this.getDataset(name);
      if (!dataset.id) {
        throw new Error(`Cannot delete dataset "${name}": ID not available`);
      }

      this.datasetBatchQueue.delete(dataset.id);
    } catch (error) {
      logger.error(`Failed to delete dataset "${name}"`, { error });
      throw new Error(`Failed to delete dataset "${name}": ${error}`);
    }
  };

  public flush = async () => {
    logger.debug("Starting flush operation");
    try {
      await this.traceBatchQueue.flush();
      await this.spanBatchQueue.flush();
      await this.traceFeedbackScoresBatchQueue.flush();
      await this.spanFeedbackScoresBatchQueue.flush();
      await this.datasetBatchQueue.flush();
      logger.info("Successfully flushed all data to Opik");
    } catch (error) {
      logger.error("Error during flush operation:", {
        error: error instanceof Error ? error.message : error,
      });
    }
  };
}
