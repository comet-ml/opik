import { ConstructorOpikConfig, loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiError, serialization } from "@/rest_api";
import type { ExperimentPublic, Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrl } from "@/utils/url";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { SpanFeedbackScoresBatchQueue } from "./SpanFeedbackScoresBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";
import { TraceFeedbackScoresBatchQueue } from "./TraceFeedbackScoresBatchQueue";
import {
  OpikApiClientTemp,
  OpikApiClientTempOptions,
} from "@/client/OpikApiClientTemp";
import { DatasetBatchQueue } from "./DatasetBatchQueue";
import { Dataset, DatasetItemData, DatasetNotFoundError } from "@/dataset";
import { Experiment } from "@/experiment/Experiment";
import { ExperimentType } from "@/rest_api/api/types";
import { ExperimentNotFoundError } from "@/errors/experiment/errors";
import { parseNdjsonStreamToArray } from "@/utils/stream";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

export const clients: OpikClient[] = [];

export class OpikClient {
  public api: OpikApiClientTemp;
  public config: OpikConfig;
  public spanBatchQueue: SpanBatchQueue;
  public traceBatchQueue: TraceBatchQueue;
  public spanFeedbackScoresBatchQueue: SpanFeedbackScoresBatchQueue;
  public traceFeedbackScoresBatchQueue: TraceFeedbackScoresBatchQueue;
  public datasetBatchQueue: DatasetBatchQueue;

  private lastProjectNameLogged: string | undefined;

  constructor(explicitConfig?: Partial<ConstructorOpikConfig>) {
    logger.debug("Initializing OpikClient with config:", explicitConfig);

    this.config = loadConfig(explicitConfig);
    const apiConfig: OpikApiClientTempOptions = {
      apiKey: this.config.apiKey,
      environment: this.config.apiUrl,
      workspaceName: this.config.workspaceName,
    };

    if (explicitConfig?.headers) {
      logger.debug(
        "Initializing OpikClient with additional headers:",
        explicitConfig?.headers
      );

      apiConfig.requestOptions = {
        headers: explicitConfig?.headers,
      };
    }

    this.api = new OpikApiClientTemp(apiConfig);

    const delay = this.config.holdUntilFlush
      ? 24 * 60 * 60 * 1000
      : this.config.batchDelayMs;

    this.spanBatchQueue = new SpanBatchQueue(this.api, delay);
    this.traceBatchQueue = new TraceBatchQueue(this.api, delay);
    this.spanFeedbackScoresBatchQueue = new SpanFeedbackScoresBatchQueue(
      this.api,
      delay
    );
    this.traceFeedbackScoresBatchQueue = new TraceFeedbackScoresBatchQueue(
      this.api,
      delay
    );
    this.datasetBatchQueue = new DatasetBatchQueue(this.api, delay);

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

  /**
   * Creates a new experiment with the given dataset name and optional parameters
   *
   * @param datasetName The name of the dataset to associate with the experiment
   * @param name Optional name for the experiment (if not provided, a generated name will be used)
   * @param experimentConfig Optional experiment configuration parameters
   * @param type Optional experiment type (defaults to "regular")
   * @param optimizationId Optional ID of an optimization associated with the experiment
   * @returns The created Experiment object
   */
  public createExperiment = async ({
    datasetName,
    name,
    experimentConfig,
    type = ExperimentType.Regular,
    optimizationId,
  }: {
    datasetName: string;
    name?: string;
    experimentConfig?: Record<string, unknown>;
    type?: ExperimentType;
    optimizationId?: string;
  }): Promise<Experiment> => {
    logger.debug(`Creating experiment for dataset "${datasetName}"`);

    if (!datasetName) {
      throw new Error("Dataset name is required to create an experiment");
    }

    const id = generateId();
    const experiment = new Experiment({ id, name, datasetName }, this);

    try {
      this.api.experiments.createExperiment({
        id,
        datasetName,
        name,
        metadata: experimentConfig,
        type,
        optimizationId,
      });

      logger.debug("Experiment added to the queue with id:", id);
      return experiment;
    } catch (error) {
      logger.error(`Failed to create experiment for dataset "${datasetName}"`, {
        error,
      });
      throw new Error(`Error creating experiment: ${error}`);
    }
  };

  /**
   * Gets an experiment by its unique ID
   *
   * @param id The unique identifier of the experiment
   * @returns The Experiment object
   */
  public getExperimentById = async (id: string): Promise<Experiment> => {
    logger.debug(`Getting experiment with ID "${id}"`);

    try {
      const experimentData = await this.api.experiments.getExperimentById(id);

      return new Experiment(
        {
          id: experimentData.id,
          name: experimentData.name,
          datasetName: experimentData.datasetName,
        },
        this
      );
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        throw new ExperimentNotFoundError(
          `No experiment found with ID '${id}'`
        );
      }
      logger.error(`Failed to get experiment with ID "${id}"`, { error });
      throw error;
    }
  };

  /**
   * Gets experiments by name (can return multiple experiments with the same name)
   *
   * @param name The name of the experiments to retrieve
   * @returns A list of Experiment objects with the given name
   */
  public getExperimentsByName = async (name: string): Promise<Experiment[]> => {
    logger.debug(`Getting experiments with name "${name}"`);

    try {
      const streamResponse = await this.api.experiments.streamExperiments({
        name,
      });

      const rawItems = await parseNdjsonStreamToArray<ExperimentPublic>(
        streamResponse,
        serialization.ExperimentPublic
      );

      return rawItems.map(
        (exp) =>
          new Experiment(
            {
              id: exp.id,
              name: exp.name,
              datasetName: exp.datasetName,
            },
            this
          )
      );
    } catch (error) {
      logger.error(`Failed to get experiments with name "${name}"`, { error });
      throw error;
    }
  };

  /**
   * Gets a single experiment by name (returns the first match if multiple exist)
   *
   * @param name The name of the experiment to retrieve
   * @returns The Experiment object
   */
  public getExperiment = async (name: string): Promise<Experiment> => {
    logger.debug(`Getting experiment with name "${name}"`);

    const experiments = await this.getExperimentsByName(name);

    if (experiments.length === 0) {
      throw new ExperimentNotFoundError(name);
    }

    return experiments[0];
  };

  /**
   * Gets all experiments associated with a dataset
   *
   * @param datasetName The name of the dataset
   * @param maxResults Maximum number of experiments to return (default: 100)
   * @returns A list of Experiment objects associated with the dataset
   * @throws {DatasetNotFoundError} If the dataset doesn't exist
   */
  public getDatasetExperiments = async (
    datasetName: string,
    maxResults: number = 100
  ): Promise<Experiment[]> => {
    logger.debug(`Getting experiments for dataset "${datasetName}"`);

    const dataset = await this.getDataset(datasetName);

    const pageSize = Math.min(100, maxResults);
    const experiments: Experiment[] = [];

    try {
      let page = 1;
      while (experiments.length < maxResults) {
        const pageExperiments = await this.api.experiments.findExperiments({
          page,
          size: pageSize,
          datasetId: dataset.id,
        });

        const content = pageExperiments?.content ?? [];

        if (content.length === 0) {
          break;
        }
        const remainingItems = maxResults - experiments.length;
        const itemsToProcess = Math.min(content.length, remainingItems);

        for (let i = 0; i < itemsToProcess; i++) {
          const exp = content[i];
          experiments.push(
            new Experiment(
              {
                id: exp.id,
                name: exp.name,
                datasetName: exp.datasetName,
              },
              this
            )
          );
        }

        if (itemsToProcess < content.length) {
          break;
        }

        page += 1;
      }

      return experiments;
    } catch (error) {
      logger.error(`Failed to get experiments for dataset "${datasetName}"`, {
        error,
      });
      throw error;
    }
  };

  /**
   * Deletes an experiment by ID
   *
   * @param id The ID of the experiment to delete
   */
  public deleteExperiment = async (id: string): Promise<void> => {
    logger.debug(`Deleting experiment with ID "${id}"`);

    try {
      await this.api.experiments.deleteExperimentsById({ ids: [id] });
    } catch (error) {
      logger.error(`Failed to delete experiment with ID "${id}"`, { error });
      throw error;
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
