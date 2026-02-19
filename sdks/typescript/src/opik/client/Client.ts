import { ConstructorOpikConfig, loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiError, serialization } from "@/rest_api";
import type { ExperimentPublic, Trace as ITrace } from "@/rest_api/api";
import * as OpikApi from "@/rest_api/api";
import { FeedbackScoreBatchItemSource } from "@/rest_api/api/types/FeedbackScoreBatchItemSource";
import { Trace } from "@/tracer/Trace";
import type { FeedbackScoreData } from "@/tracer/types";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrlByTraceId } from "@/utils/url";
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
import { buildMetadataAndPromptVersions } from "@/experiment/helpers";
import { ExperimentType } from "@/rest_api/api/types";
import { ExperimentNotFoundError } from "@/errors/experiment/errors";
import { parseNdjsonStreamToArray } from "@/utils/stream";
import {
  Prompt,
  CreatePromptOptions,
  GetPromptOptions,
  PromptType,
} from "@/prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { PromptTemplateStructure, type CreateChatPromptOptions, type CommonPromptOptions } from "@/prompt/types";
import { PromptTemplateStructureMismatch } from "@/prompt/errors";
import {
  fetchLatestPromptVersion,
  shouldCreateNewVersion,
} from "@/prompt/versionHelpers";
import { OpikQueryLanguage } from "@/query";
import {
  searchTracesWithFilters,
  searchThreadsWithFilters,
  searchSpansWithFilters,
  searchAndWaitForDone,
  parseFilterString,
  parseThreadFilterString,
  parseSpanFilterString,
} from "@/utils/searchHelpers";
import { SearchTimeoutError } from "@/errors";
import {
  AnnotationQueueNotFoundError,
  TracesAnnotationQueue,
  ThreadsAnnotationQueue,
} from "@/annotation-queue";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

interface AnnotationQueueOptions {
  name: string;
  projectName?: string;
  description?: string;
  instructions?: string;
  commentsEnabled?: boolean;
  feedbackDefinitionNames?: string[];
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

  private displayTraceLog = (traceId: string, projectName: string) => {
    if (projectName === this.lastProjectNameLogged || !this.config.apiUrl) {
      return;
    }

    const projectUrl = getProjectUrlByTraceId(traceId, this.config.apiUrl);

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
    this.displayTraceLog(trace.data.id, projectName);

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



  private async getProjectIdByName(projectName: string): Promise<string> {
    const project = await this.api.projects.retrieveProject({
      name: projectName,
    });

    if (!project?.id) {
      throw new Error(`Project "${projectName}" not found`);
    }
    return project.id;
  }

  private async createAnnotationQueueInternal<T extends TracesAnnotationQueue | ThreadsAnnotationQueue>(
    options: AnnotationQueueOptions,
    QueueClass: (new (data: OpikApi.AnnotationQueuePublic, opik: OpikClient) => T) & {
      readonly SCOPE: "trace" | "thread";
    }
  ): Promise<T> {
    const {
      name,
      projectName,
      description,
      instructions,
      commentsEnabled,
      feedbackDefinitionNames,
    } = options;

    const scope = QueueClass.SCOPE;

    logger.debug(`Creating ${scope} annotation queue "${name}"`);

    const targetProjectName = projectName ?? this.config.projectName;

    try {
      const projectId = await this.getProjectIdByName(targetProjectName);
      const queueId = generateId();

      await this.api.annotationQueues.createAnnotationQueue({
        id: queueId,
        projectId,
        name,
        scope,
        description,
        instructions,
        commentsEnabled,
        feedbackDefinitionNames,
      });

      logger.debug(`Created ${scope} annotation queue "${name}" with ID "${queueId}"`);

      return new QueueClass(
        {
          id: queueId,
          name,
          projectId,
          scope,
          description,
          instructions,
          commentsEnabled,
          feedbackDefinitionNames,
        },
        this
      );
    } catch (error) {
      logger.error(`Failed to create ${scope} annotation queue "${name}"`, { error });
      throw error;
    }
  }

  /**
   * Creates a new traces annotation queue for human annotation workflows.
   *
   * @param options - Configuration options for the annotation queue
   * @param options.name - The name of the annotation queue
   * @param options.projectName - Optional project name (defaults to client's configured project)
   * @param options.description - Optional description of the queue
   * @param options.instructions - Optional instructions for reviewers
   * @param options.commentsEnabled - Optional flag to enable/disable comments
   * @param options.feedbackDefinitionNames - Optional list of feedback definition names
   * @returns The created TracesAnnotationQueue object
   */
  public createTracesAnnotationQueue = async (options: AnnotationQueueOptions): Promise<TracesAnnotationQueue> => {
    return this.createAnnotationQueueInternal(options, TracesAnnotationQueue);
  };

  /**
   * Creates a new threads annotation queue for human annotation workflows.
   *
   * @param options - Configuration options for the annotation queue
   * @param options.name - The name of the annotation queue
   * @param options.projectName - Optional project name (defaults to client's configured project)
   * @param options.description - Optional description of the queue
   * @param options.instructions - Optional instructions for reviewers
   * @param options.commentsEnabled - Optional flag to enable/disable comments
   * @param options.feedbackDefinitionNames - Optional list of feedback definition names
   * @returns The created ThreadsAnnotationQueue object
   */
  public createThreadsAnnotationQueue = async (options: AnnotationQueueOptions): Promise<ThreadsAnnotationQueue> => {
    return this.createAnnotationQueueInternal(options, ThreadsAnnotationQueue);
  };

  private async fetchAnnotationQueueById<T extends TracesAnnotationQueue | ThreadsAnnotationQueue>(
    id: string,
    expectedScope: "trace" | "thread",
    QueueClass: new (data: OpikApi.AnnotationQueuePublic, opik: OpikClient) => T
  ): Promise<T> {
    logger.debug(`Getting ${expectedScope} annotation queue with ID "${id}"`);

    try {
      const response = await this.api.annotationQueues.getAnnotationQueueById(id);

      if (response.scope !== expectedScope) {
        throw new Error(`Annotation queue "${id}" is not a ${expectedScope} queue (scope: ${response.scope})`);
      }

      return new QueueClass(response, this);
    } catch (error) {
      if (error instanceof OpikApiError) {
        if (error.statusCode === 404) {
          throw new AnnotationQueueNotFoundError(id);
        }
        logger.error(`Failed to get ${expectedScope} annotation queue with ID "${id}"`, { error });
      }
      throw error;
    }
  }

  /**
   * Retrieves a traces annotation queue by its ID.
   *
   * @param id - The unique identifier of the annotation queue
   * @returns The TracesAnnotationQueue object
   * @throws AnnotationQueueNotFoundError if the queue doesn't exist or is not a traces queue
   */
  public getTracesAnnotationQueue = async (id: string): Promise<TracesAnnotationQueue> => {
    return this.fetchAnnotationQueueById(id, "trace", TracesAnnotationQueue);
  };

  /**
   * Retrieves a threads annotation queue by its ID.
   *
   * @param id - The unique identifier of the annotation queue
   * @returns The ThreadsAnnotationQueue object
   * @throws AnnotationQueueNotFoundError if the queue doesn't exist or is not a threads queue
   */
  public getThreadsAnnotationQueue = async (id: string): Promise<ThreadsAnnotationQueue> => {
    return this.fetchAnnotationQueueById(id, "thread", ThreadsAnnotationQueue);
  };

  /**
   * Retrieves all traces annotation queues, optionally filtered by project.
   *
   * @param options - Optional configuration
   * @param options.projectName - Optional project name to filter by
   * @param options.maxResults - Maximum number of results to return (default: 1000)
   * @returns List of TracesAnnotationQueue objects
   */
  public getTracesAnnotationQueues = async (options?: {
    projectName?: string;
    maxResults?: number;
  }): Promise<TracesAnnotationQueue[]> => {
    const queues = await this.getAnnotationQueuesByScope("trace", options);
    return queues.map(queueData => new TracesAnnotationQueue(queueData, this));
  };

  /**
   * Retrieves all threads annotation queues, optionally filtered by project.
   *
   * @param options - Optional configuration
   * @param options.projectName - Optional project name to filter by
   * @param options.maxResults - Maximum number of results to return (default: 1000)
   * @returns List of ThreadsAnnotationQueue objects
   */
  public getThreadsAnnotationQueues = async (options?: {
    projectName?: string;
    maxResults?: number;
  }): Promise<ThreadsAnnotationQueue[]> => {
    const queues = await this.getAnnotationQueuesByScope("thread", options);
    return queues.map(queueData => new ThreadsAnnotationQueue(queueData, this));
  };

  private async getAnnotationQueuesByScope(
    scope: "trace" | "thread",
    options?: {
      projectName?: string;
      maxResults?: number;
    }
  ): Promise<OpikApi.AnnotationQueuePublic[]> {
    const { projectName, maxResults = 1000 } = options ?? {};

    logger.debug(
      `Getting ${scope} annotation queues (project: ${projectName ?? "all"}, limit: ${maxResults})`
    );

    try {
      let filters: string | undefined;

      if (projectName) {
        const projectId = await this.getProjectIdByName(projectName);
        filters = JSON.stringify([
          { field: "project_id", operator: "=", value: projectId },
          { field: "scope", operator: "=", value: scope },
        ]);
      } else {
        filters = JSON.stringify([
          { field: "scope", operator: "=", value: scope },
        ]);
      }

      const response = await this.api.annotationQueues.findAnnotationQueues({
        size: maxResults,
        filters,
      });

      const queues = response.content || [];
      logger.info(`Retrieved ${queues.length} ${scope} annotation queues`);
      return queues;
    } catch (error) {
      logger.error(`Failed to retrieve ${scope} annotation queues`, { error });
      throw error;
    }
  }

  private async deleteAnnotationQueueById(id: string, scope: "traces" | "threads"): Promise<void> {
    logger.debug(`Deleting ${scope} annotation queue with ID "${id}"`);

    try {
      await this.api.annotationQueues.deleteAnnotationQueueBatch({
        ids: [id],
      });

      logger.debug(`Successfully deleted ${scope} annotation queue with ID "${id}"`);
    } catch (error) {
      logger.error(`Failed to delete ${scope} annotation queue with ID "${id}"`, {
        error,
      });
      throw error;
    }
  }

  /**
   * Deletes a traces annotation queue by its ID.
   *
   * @param id - The ID of the traces annotation queue to delete
   */
  public deleteTracesAnnotationQueue = async (id: string): Promise<void> => {
    return this.deleteAnnotationQueueById(id, "traces");
  };

  /**
   * Deletes a threads annotation queue by its ID.
   *
   * @param id - The ID of the threads annotation queue to delete
   */
  public deleteThreadsAnnotationQueue = async (id: string): Promise<void> => {
    return this.deleteAnnotationQueueById(id, "threads");
  };

  /**
   * Creates a new experiment with the given dataset name and optional parameters
   *
   * @param datasetName The name of the dataset to associate with the experiment
   * @param name Optional name for the experiment (if not provided, a generated name will be used)
   * @param experimentConfig Optional experiment configuration parameters
   * @param prompts Optional array of Prompt objects to link with the experiment
   * @param type Optional experiment type (defaults to "regular")
   * @param optimizationId Optional ID of an optimization associated with the experiment
   * @param datasetVersionId Optional ID of the dataset version to link the experiment to
   * @returns The created Experiment object
   */
  public createExperiment = async ({
    datasetName,
    name,
    experimentConfig,
    prompts,
    type = ExperimentType.Regular,
    optimizationId,
    datasetVersionId,
  }: {
    datasetName: string;
    name?: string;
    experimentConfig?: Record<string, unknown>;
    prompts?: Prompt[];
    type?: ExperimentType;
    optimizationId?: string;
    datasetVersionId?: string;
  }): Promise<Experiment> => {
    logger.debug(`Creating experiment for dataset "${datasetName}"`);

    if (!datasetName) {
      throw new Error("Dataset name is required to create an experiment");
    }

    // Process prompts and build metadata
    const [metadata, promptVersions] = buildMetadataAndPromptVersions(
      experimentConfig,
      prompts
    );

    const id = generateId();
    const experiment = new Experiment({ id, name, datasetName, prompts }, this);

    try {
      await this.api.experiments.createExperiment({
        id,
        datasetName,
        name,
        metadata,
        promptVersions,
        type,
        optimizationId,
        datasetVersionId,
      });

      logger.debug("Experiment created with id:", id);
      return experiment;
    } catch (error) {
      logger.error(`Failed to create experiment for dataset "${datasetName}"`, {
        error,
      });
      throw new Error(`Error creating experiment: ${error}`);
    }
  };

    /**
     * Updates an experiment by ID
     *
     * @param id The ID of the experiment
     * @param experimentUpdate Object containing the fields to update
     * @param experimentUpdate.name Optional new name for the experiment
     * @param experimentUpdate.experimentConfig Optional new configuration for the experiment
     * @returns Promise that resolves when the experiment is updated
     * @throws {Error} If id is not provided or if neither name nor experimentConfig is provided
     */
    public updateExperiment = async (
        id: string,
        experimentUpdate: {
            name?: string;
            experimentConfig?: Record<string, unknown>;
        }
    ): Promise<void> => {
        if (!id) {
            throw new Error("id is required to update an experiment");
        }

        const { name, experimentConfig } = experimentUpdate;

        if (!name && !experimentConfig) {
            throw new Error("At least one of 'name' or 'experimentConfig' must be provided to update an experiment");
        }

        logger.debug(`Updating experiment with ID "${id}"`);

        // Only include parameters that are provided to avoid clearing fields
        const request: OpikApi.ExperimentUpdate = {};
        if (name !== undefined) {
            request.name = name;
        }
        if (experimentConfig !== undefined) {
            request.metadata = experimentConfig;
        }

        try {
            await this.api.experiments.updateExperiment(id, { body: request });
        } catch (error) {
            logger.error(`Failed to update experiment with ID "${id}"`, { error });
            throw error;
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

  /**
   * Internal helper for creating prompts (text or chat).
   * Handles common logic: version checking, creation, and property updates.
   *
   * @param name - Prompt name
   * @param template - Template string (raw text or JSON-serialized messages)
   * @param templateStructure - Text or Chat structure
   * @param options - Common prompt options (metadata, type, description, tags)
   * @param validateStructure - Callback to validate template structure against existing prompt
   * @param createInstance - Factory function to create Prompt or ChatPrompt instance
   * @param logContext - Context string for logging (e.g., "prompt" or "chat prompt")
   * @returns Promise resolving to Prompt or ChatPrompt instance
   */
  private createPromptInternal = async <T extends Prompt | ChatPrompt>(
    name: string,
    template: string,
    templateStructure: PromptTemplateStructure,
    options: CommonPromptOptions,
    validateStructure: (latest: OpikApi.PromptVersionDetail | null) => void,
    createInstance: (
      promptData: OpikApi.PromptPublic,
      versionData: OpikApi.PromptVersionDetail
    ) => T,
    logContext: string
  ): Promise<T> => {
    logger.debug(`Creating ${logContext}`, { name });

    try {
      // Fetch latest version (returns null if prompt doesn't exist yet)
      const latestVersion = await fetchLatestPromptVersion(
        this.api.prompts,
        name,
        this.api.requestOptions
      );

      // Validate template structure against existing prompt
      validateStructure(latestVersion);

      // Determine if we need to create a new version
      const normalizedType = options.type ?? PromptType.MUSTACHE;
      const needsNewVersion = shouldCreateNewVersion(
        { prompt: template, metadata: options.metadata },
        latestVersion,
        normalizedType
      );

      let versionResponse: OpikApi.PromptVersionDetail;

      if (needsNewVersion) {
        // Create new version
        logger.debug(`Creating new ${logContext} version`, { name });
        versionResponse = await this.api.prompts.createPromptVersion(
          {
            name,
            version: {
              template,
              metadata: options.metadata,
              type: normalizedType,
            },
            templateStructure,
          },
          this.api.requestOptions
        );
      } else {
        // Return existing version (idempotent)
        logger.debug(`Returning existing ${logContext} version`, { name });
        versionResponse = latestVersion!;
      }

      // Fetch full prompt data and create instance
      if (!versionResponse.promptId) {
        throw new Error("Invalid API response: missing promptId");
      }

      const promptData = await this.api.prompts.getPromptById(
        versionResponse.promptId,
        this.api.requestOptions
      );

      const promptInstance = createInstance(promptData, versionResponse) as T;

      logger.debug(`${logContext} created`, { name });

      // Update properties if provided
      if (options.description || options.tags) {
        return (await promptInstance.updateProperties({
          description: options.description,
          tags: options.tags,
        })) as T;
      }

      return promptInstance;
    } catch (error) {
      logger.error(`Failed to create ${logContext}`, { name, error });
      throw error;
    }
  };

  /**
   * Creates a new prompt or new version if content differs.
   *
   * Key Behaviors:
   * - Smart Versioning: Only creates a new version if template, metadata, or type differ from latest
   * - Idempotent: Returns existing version if identical (no duplicate versions)
   * - 404 Handling: Gracefully handles first-time prompt creation
   * - Uses create_prompt_version endpoint (not create_prompt which is for containers)
   * - Synchronous: Returns immediately with the created/retrieved version
   *
   * @param options - Prompt configuration
   * @returns Promise resolving to Prompt instance
   * @throws PromptValidationError if parameters invalid
   */
  public createPrompt = async (
    options: CreatePromptOptions
  ): Promise<Prompt> => {
    return this.createPromptInternal(
      options.name,
      options.prompt,
      PromptTemplateStructure.Text,
      options,
      () => {
        // No structure validation needed for text prompts
      },
      (promptData, versionData) =>
        Prompt.fromApiResponse(promptData, versionData, this),
      "prompt"
    );
  };

  /**
   * Creates a new chat prompt or returns existing one if identical.
   * Chat prompts use message arrays instead of string templates.
   * Idempotent: returns existing version if messages, metadata, and type match.
   *
   * @param options - Chat prompt configuration with messages array
   * @returns Promise resolving to ChatPrompt instance
   * @throws PromptTemplateStructureMismatch if a text prompt with same name exists
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.createChatPrompt({
   *   name: "assistant-prompt",
   *   messages: [
   *     { role: "system", content: "You are a helpful assistant" },
   *     { role: "user", content: "Help me with {{task}}" }
   *   ],
   *   type: "mustache"
   * });
   * ```
   */
  public createChatPrompt = async (
    options: CreateChatPromptOptions
  ): Promise<ChatPrompt> => {
    // Serialize messages to JSON for backend storage
    const messagesJson = JSON.stringify(options.messages);

    return this.createPromptInternal(
      options.name,
      messagesJson,
      PromptTemplateStructure.Chat,
      options,
      (latestVersion) => {
        // Check for template structure mismatch
        if (
          latestVersion &&
          latestVersion.templateStructure &&
          latestVersion.templateStructure !== PromptTemplateStructure.Chat
        ) {
          throw new PromptTemplateStructureMismatch(
            options.name,
            latestVersion.templateStructure,
            PromptTemplateStructure.Chat
          );
        }
      },
      (promptData, versionData) =>
        ChatPrompt.fromApiResponse(promptData, versionData, this),
      "chat prompt"
    );
  };

  /**
   * Retrieves a text prompt by name and optional version.
   * Throws PromptTemplateStructureMismatch if the prompt is a chat prompt.
   *
   * @param options - Prompt name and optional commit hash
   * @returns Promise resolving to Prompt or null if not found
   * @throws PromptTemplateStructureMismatch if prompt exists but is a chat prompt
   */
  public getPrompt = async (
    options: GetPromptOptions
  ): Promise<Prompt | null> => {
    logger.debug("Getting prompt", options);

    try {
      // Step 1: Search for the prompt by name to get tags and description
      const searchResponse = await this.api.prompts.getPrompts(
        {
          filters: JSON.stringify([
            { field: "name", operator: "=", value: options.name },
          ]),
          size: 1,
        },
        this.api.requestOptions
      );

      const promptData = searchResponse.content?.[0];
      if (!promptData) {
        logger.debug("Prompt not found", { name: options.name });
        return null;
      }

      // Step 2: Get the version (latest if no commit specified)
      const versionData = await this.api.prompts.retrievePromptVersion(
        options,
        this.api.requestOptions
      );

      // Step 3: Validate template structure
      const templateStructure = versionData.templateStructure;
      if (templateStructure && templateStructure !== PromptTemplateStructure.Text) {
        throw new PromptTemplateStructureMismatch(
          options.name,
          templateStructure,
          PromptTemplateStructure.Text
        );
      }

      // Step 4: Create the Prompt object with metadata
      return Prompt.fromApiResponse(promptData, versionData, this);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return null;
      }
      logger.error("Failed to get prompt", { name: options.name, error });
      throw error;
    }
  };

  /**
   * Retrieves a chat prompt by name and optional version.
   * Throws PromptTemplateStructureMismatch if the prompt is a text prompt.
   *
   * @param options - Prompt name and optional commit hash
   * @returns Promise resolving to ChatPrompt or null if not found
   * @throws PromptTemplateStructureMismatch if prompt exists but is a text prompt
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.getChatPrompt({ name: "assistant-prompt" });
   * if (chatPrompt) {
   *   const messages = chatPrompt.format({ task: "coding" });
   * }
   * ```
   */
  public getChatPrompt = async (
    options: GetPromptOptions
  ): Promise<ChatPrompt | null> => {
    logger.debug("Getting chat prompt", options);

    try {
      // Step 1: Search for the prompt by name to get tags and description
      const searchResponse = await this.api.prompts.getPrompts(
        {
          filters: JSON.stringify([
            { field: "name", operator: "=", value: options.name },
          ]),
          size: 1,
        },
        this.api.requestOptions
      );

      const promptData = searchResponse.content?.[0];
      if (!promptData) {
        logger.debug("Chat prompt not found", { name: options.name });
        return null;
      }

      // Step 2: Get the version (latest if no commit specified)
      const versionData = await this.api.prompts.retrievePromptVersion(
        options,
        this.api.requestOptions
      );

      // Step 3: Validate template structure
      const templateStructure = versionData.templateStructure;
      if (!templateStructure || templateStructure !== PromptTemplateStructure.Chat) {
        throw new PromptTemplateStructureMismatch(
          options.name,
          templateStructure ?? "undefined",
          PromptTemplateStructure.Chat
        );
      }

      // Step 4: Create the ChatPrompt object with metadata
      return ChatPrompt.fromApiResponse(promptData, versionData, this);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return null;
      }
      logger.error("Failed to get chat prompt", {
        name: options.name,
        error,
      });
      throw error;
    }
  };

  /**
   * Searches prompts with optional OQL filtering.
   *
   * @param filterString - Optional OQL filter string to narrow down search
   *
   * Supported OQL format: `<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*`
   *
   * Supported columns:
   * - `id`, `name`, `description`: String fields
   * - `created_by`, `last_updated_by`: String fields
   * - `template_structure`: String field (e.g., "text" or "chat")
   * - `created_at`, `last_updated_at`: Date/time fields (ISO 8601 format)
   * - `tags`: List field (use "contains" operator only)
   * - `version_count`: Number field
   *
   * Supported operators by column:
   * - String fields (`id`, `name`, `description`, `created_by`, `last_updated_by`, `template_structure`): =, !=, contains, not_contains, starts_with, ends_with, >, <
   * - Date/time fields (`created_at`, `last_updated_at`): =, >, <, >=, <=
   * - Number fields (`version_count`): =, !=, >, <, >=, <=
   * - List fields (`tags`): contains
   *
   * @returns Promise resolving to array of matching latest prompt versions
   * @throws Error if OQL filter syntax is invalid
   *
   * @example
   * ```typescript
   * // Get all prompts
   * const allPrompts = await client.searchPrompts();
   *
   * // Filter by tag
   * const prompts = await client.searchPrompts('tags contains "alpha"');
   *
   * // Filter by multiple criteria
   * const prompts = await client.searchPrompts(
   *   'tags contains "alpha" AND name contains "summary"'
   * );
   *
   * // Filter by creator
   * const prompts = await client.searchPrompts('created_by = "user@example.com"');
   *
   * // Filter by template structure
   * const chatPrompts = await client.searchPrompts('template_structure = "chat"');
   *
   * // Filter by date range
   * const recentPrompts = await client.searchPrompts('created_at >= "2024-01-01T00:00:00Z"');
   *
   * // Filter by version count
   * const multiVersion = await client.searchPrompts('version_count > 5');
   * ```
   */
  public searchPrompts = async (
    filterString?: string
  ): Promise<(Prompt | ChatPrompt)[]> => {
    logger.debug("Searching prompts", { filterString });

    try {
      // Parse OQL filter string to JSON
      let filters: string | undefined;
      if (filterString) {
        const oql = OpikQueryLanguage.forPrompts(filterString);
        const filterExpressions = oql.getFilterExpressions();
        filters = filterExpressions
          ? JSON.stringify(filterExpressions)
          : undefined;
      }

      const response = await this.api.prompts.getPrompts(
        {
          filters,
          size: 1000,
        },
        this.api.requestOptions
      );

      const prompts = response.content ?? [];

      // Map each prompt to get its latest version and create appropriate instance
      const promptsWithVersions = await Promise.all(
        prompts.map(async (promptData: OpikApi.PromptPublic) => {
          if (!promptData.name) {
            return null;
          }

          try {
            const versionResponse =
              await this.api.prompts.retrievePromptVersion(
                { name: promptData.name },
                this.api.requestOptions
              );

            const templateStructure = versionResponse.templateStructure;

            // Default to text for backwards compatibility
            if (!templateStructure || templateStructure === PromptTemplateStructure.Text) {
              return Prompt.fromApiResponse(promptData, versionResponse, this);
            } else if (templateStructure === PromptTemplateStructure.Chat) {
              return ChatPrompt.fromApiResponse(
                promptData,
                versionResponse,
                this
              );
            }

            return null;
          } catch (error) {
            logger.debug("Failed to get version for prompt", {
              name: promptData.name,
              error,
            });
            return null;
          }
        })
      );

      return promptsWithVersions.filter(
        (p: Prompt | ChatPrompt | null): p is Prompt | ChatPrompt => p !== null
      );
    } catch (error) {
      logger.error("Failed to search prompts", { error });
      throw error;
    }
  };

  /**
   * Deletes multiple prompts and all their versions in batch.
   * Performs synchronous deletion (no batching).
   *
   * @param ids - Array of prompt container IDs to delete
   */
  public deletePrompts = async (ids: string[]): Promise<void> => {
    logger.debug("Deleting prompts in batch", { count: ids.length });

    try {
      await this.api.prompts.deletePromptsBatch(
        { ids },
        this.api.requestOptions
      );

      logger.info("Successfully deleted prompts", { count: ids.length });
    } catch (error) {
      logger.error("Failed to delete prompts", { count: ids.length, error });
      throw error;
    }
  };

  /**
   * Search for traces in the given project. Optionally, you can wait for at least a certain number of traces
   * to be found before returning within the specified timeout.
   *
   * @param projectName - The name of the project to search in. Defaults to the project configured on the Client.
   * @param filterString - Filter using Opik Query Language (OQL). Format: `<COLUMN> <OPERATOR> <VALUE> [AND ...]`
   *   Common columns: `id`, `name`, `start_time`, `end_time`, `input`, `output`, `status`, `tags`, `metadata.*`, `feedback_scores.*`, `usage.*`
   *   Common operators: `=`, `!=`, `>`, `<`, `>=`, `<=`, `contains`, `not_contains`, `starts_with`, `ends_with`
   *   Use ISO 8601 format for dates (e.g., "2024-01-01T00:00:00Z")
   * @param maxResults - Maximum number of traces to return (default: 1000)
   * @param truncate - Whether to truncate image data in input, output, or metadata (default: true)
   * @param waitForAtLeast - Minimum number of traces to wait for before returning
   * @param waitForTimeout - Timeout for waiting in seconds (default: 60)
   *
   * @returns Promise resolving to array of traces matching the search criteria
   * @throws {SearchTimeoutError} If waitForAtLeast traces are not found within the specified timeout
   *
   * @example
   * ```typescript
   * // Get all traces in a project
   * const traces = await client.searchTraces({ projectName: "My Project" });
   *
   * // Filter by date and metadata
   * const filtered = await client.searchTraces({
   *   projectName: "My Project",
   *   filterString: 'start_time >= "2024-01-01T00:00:00Z" AND metadata.model = "gpt-4"'
   * });
   *
   * // Wait for at least 10 traces
   * const traces = await client.searchTraces({
   *   projectName: "My Project",
   *   waitForAtLeast: 10,
   *   waitForTimeout: 30
   * });
   * ```
   */
  private async executeSearch<T, TFilter>(
    resourceType: "traces" | "threads" | "spans",
    options: {
      projectName?: string;
      filterString?: string;
      maxResults?: number;
      truncate?: boolean;
      waitForAtLeast?: number;
      waitForTimeout?: number;
    },
    parseFilters: (filterString?: string) => TFilter[] | null,
    searchWithFilters: (
      api: OpikApiClientTemp,
      projectName: string,
      filters: TFilter[] | null,
      maxResults: number,
      truncate: boolean
    ) => Promise<T[]>
  ): Promise<T[]> {
    const {
      projectName,
      filterString,
      maxResults = 1000,
      truncate = true,
      waitForAtLeast,
      waitForTimeout = 60,
    } = options;

    logger.debug(`Searching ${resourceType}`, {
      projectName,
      filterString,
      maxResults,
      truncate,
      waitForAtLeast,
      waitForTimeout,
    });

    const filters = parseFilters(filterString);
    const targetProject = projectName ?? this.config.projectName;

    const searchFn = () =>
      searchWithFilters(
        this.api,
        targetProject,
        filters,
        maxResults,
        truncate
      );

    if (waitForAtLeast === undefined) {
      return await searchFn();
    }

    const result = await searchAndWaitForDone(
      searchFn,
      waitForAtLeast,
      waitForTimeout * 1000,
      5000
    );

    if (result.length < waitForAtLeast) {
      throw new SearchTimeoutError(
        `Timeout after ${waitForTimeout} seconds: expected ${waitForAtLeast} ${resourceType}, but only ${result.length} were found.`
      );
    }

    return result;
  }

  public searchTraces = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.TracePublic[]> => {
    return this.executeSearch<OpikApi.TracePublic, OpikApi.TraceFilterPublic>(
      "traces",
      options ?? {},
      parseFilterString,
      searchTracesWithFilters
    );
  };

  /**
   * Search for threads in a project with optional filtering.
   *
   * Threads represent conversations or sessions that group related traces together.
   * This method allows you to search and filter threads using Opik Query Language (OQL).
   *
   * @param options - Search options
   * @param options.projectName - Name of the project to search in. Defaults to the client's configured project.
   * @param options.filterString - Filter string using Opik Query Language (OQL).
   *   Supports filtering by: id, status, feedback_scores, duration, number_of_messages, tags, metadata, etc.
   *   Examples: 'status = "active"', 'feedback_scores.quality > 0.8', 'duration > 300'
   * @param options.maxResults - Maximum number of threads to return (default: 1000)
   * @param options.truncate - Whether to truncate large fields in the response (default: true)
   * @param options.waitForAtLeast - If specified, polls until at least this many threads are found
   * @param options.waitForTimeout - Timeout in seconds when using waitForAtLeast (default: 60)
   * @returns Promise resolving to an array of threads
   * @throws {SearchTimeoutError} If waitForAtLeast is specified and timeout is reached
   *
   * @example
   * ```typescript
   * // Get all threads in a project
   * const threads = await client.searchThreads({ projectName: "My Project" });
   *
   * // Filter by status
   * const activeThreads = await client.searchThreads({
   *   projectName: "My Project",
   *   filterString: 'status = "active"'
   * });
   *
   * // Filter by feedback score
   * const highQualityThreads = await client.searchThreads({
   *   projectName: "My Project",
   *   filterString: 'feedback_scores.quality > 0.8'
   * });
   *
   * // Wait for at least 5 threads
   * const threads = await client.searchThreads({
   *   projectName: "My Project",
   *   waitForAtLeast: 5,
   *   waitForTimeout: 30
   * });
   * ```
   */
  public searchThreads = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.TraceThread[]> => {
    return this.executeSearch<OpikApi.TraceThread, OpikApi.TraceThreadFilter>(
      "threads",
      options ?? {},
      parseThreadFilterString,
      searchThreadsWithFilters
    );
  };

  /**
   * Search for spans in a project with optional filtering.
   *
   * Spans represent individual operations or steps within traces, such as LLM calls or function executions.
   * This method allows you to search and filter spans using Opik Query Language (OQL).
   *
   * @param options - Search options
   * @param options.projectName - Name of the project to search in. Defaults to the client's configured project.
   * @param options.filterString - Filter string using Opik Query Language (OQL).
   *   Supports filtering by: model, provider, type, metadata, feedback_scores, usage, duration, etc.
   *   Examples: 'model = "gpt-4"', 'provider = "openai"', 'type = "llm"', 'metadata.version = "1.0"'
   * @param options.maxResults - Maximum number of spans to return (default: 1000)
   * @param options.truncate - Whether to truncate large fields in the response (default: true)
   * @param options.waitForAtLeast - If specified, polls until at least this many spans are found
   * @param options.waitForTimeout - Timeout in seconds when using waitForAtLeast (default: 60)
   * @returns Promise resolving to an array of spans
   * @throws {SearchTimeoutError} If waitForAtLeast is specified and timeout is reached
   *
   * @example
   * ```typescript
   * // Get all spans in a project
   * const spans = await client.searchSpans({ projectName: "My Project" });
   *
   * // Filter by model
   * const gpt4Spans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'model = "gpt-4"'
   * });
   *
   * // Filter by provider and type
   * const openaiLLMSpans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'provider = "openai" and type = "llm"'
   * });
   *
   * // Filter by metadata
   * const prodSpans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'metadata.environment = "production"'
   * });
   *
   * // Wait for at least 5 spans
   * const spans = await client.searchSpans({
   *   projectName: "My Project",
   *   waitForAtLeast: 5,
   *   waitForTimeout: 30
   * });
   * ```
   */
  public searchSpans = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.SpanPublic[]> => {
    return this.executeSearch<OpikApi.SpanPublic, OpikApi.SpanFilterPublic>(
      "spans",
      options ?? {},
      parseSpanFilterString,
      searchSpansWithFilters
    );
  };

  private logFeedbackScores(
    scores: FeedbackScoreData[],
    batchQueue: TraceFeedbackScoresBatchQueue | SpanFeedbackScoresBatchQueue
  ): void {
    for (const score of scores) {
      batchQueue.create({
        ...score,
        projectName: score.projectName ?? this.config.projectName,
        source: FeedbackScoreBatchItemSource.Sdk,
      });
    }
  }

  /**
   * Log feedback scores to existing traces in batch.
   *
   * @param scores - Array of feedback score data with trace IDs
   *
   * @example
   * ```typescript
   * client.logTracesFeedbackScores([
   *   { id: "trace-id-1", name: "quality", value: 0.9, reason: "Good response" },
   *   { id: "trace-id-2", name: "relevance", value: 0.8 }
   * ]);
   * await client.flush();
   * ```
   */
  public logTracesFeedbackScores(scores: FeedbackScoreData[]): void {
    this.logFeedbackScores(scores, this.traceFeedbackScoresBatchQueue);
  }

  /**
   * Log feedback scores to existing spans in batch.
   *
   * @param scores - Array of feedback score data with span IDs
   *
   * @example
   * ```typescript
   * client.logSpansFeedbackScores([
   *   { id: "span-id-1", name: "accuracy", value: 0.95 },
   *   { id: "span-id-2", name: "completeness", value: 0.85, reason: "Missing details" }
   * ]);
   * await client.flush();
   * ```
   */
  public logSpansFeedbackScores(scores: FeedbackScoreData[]): void {
    this.logFeedbackScores(scores, this.spanFeedbackScoresBatchQueue);
  }

  public flush = async () => {
    logger.debug("Starting flush operation");
    try {
      await this.traceBatchQueue.flush();
      await this.spanBatchQueue.flush();
      await this.traceFeedbackScoresBatchQueue.flush();
      await this.spanFeedbackScoresBatchQueue.flush();
      await this.datasetBatchQueue.flush();
      // Note: Prompt operations are synchronous and don't use batching
      logger.info("Successfully flushed all data to Opik");
    } catch (error) {
      logger.error("Error during flush operation:", {
        error: error instanceof Error ? error.message : error,
      });
    }
  };

  /**
   * Updates tags for one or more prompt versions in a single batch operation.
   *
   * @param versionIds - Array of prompt version IDs to update
   * @param options - Update options
   * @param options.tags - Tags to set or merge:
   *   - `[]`: Clear all tags (when mergeTags is false or unspecified)
   *   - `['tag1', 'tag2']`: Set or merge tags (based on mergeTags)
   * @param options.mergeTags - If true, adds new tags to existing tags (union). If false, replaces all existing tags (default: false)
   * @returns Promise that resolves when update is complete
   * @throws OpikApiError if update fails
   *
   * @example
   * ```typescript
   * // Replace tags on multiple versions (default behavior)
   * await client.updatePromptVersionTags(["version-id-1", "version-id-2"], {
   *   tags: ["production", "v2"]
   * });
   *
   * // Merge new tags with existing tags
   * await client.updatePromptVersionTags(["version-id-1"], {
   *   tags: ["hotfix"],
   *   mergeTags: true
   * });
   *
   * // Clear all tags
   * await client.updatePromptVersionTags(["version-id-1"], {
   *   tags: []
   * });
   * ```
   */
  public updatePromptVersionTags = async (
    versionIds: string[],
    options?: {
      tags?: string[] | null;
      mergeTags?: boolean;
    }
  ): Promise<void> => {
    logger.debug("Updating prompt version tags", {
      count: versionIds.length,
      options,
    });

    try {
      await this.api.prompts.updatePromptVersions(
        {
          ids: versionIds,
          update: { tags: options?.tags ?? undefined },
          mergeTags: options?.mergeTags,
        },
        this.api.requestOptions
      );

      logger.debug("Successfully updated prompt version tags", {
        count: versionIds.length,
      });
    } catch (error) {
      logger.error("Failed to update prompt version tags", {
        count: versionIds.length,
        error,
      });
      throw error;
    }
  };
}
