import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient, OpikApiError } from "@/rest_api";
import type { DatasetPublic, Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrl } from "@/utils/url";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { SpanFeedbackScoresBatchQueue } from "./SpanFeedbackScoresBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";
import { TraceFeedbackScoresBatchQueue } from "./TraceFeedbackScoresBatchQueue";
import { Dataset } from "@/dataset/Dataset";

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
      this.api,
    );
    this.traceFeedbackScoresBatchQueue = new TraceFeedbackScoresBatchQueue(
      this.api,
    );

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
      `Started logging traces to the "${projectName}" project at ${createLink(projectUrl)}`,
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
      this,
    );

    this.traceBatchQueue.create(trace.data);
    logger.debug("Trace added to the queue with ID:", trace.data.id);
    this.displayTraceLog(projectName);

    return trace;
  };

  public getOrCreateDataset = async (name: string, description?: string) => {
    try {
      const dataset = await this.createDataset(name, description);
      return dataset;
    } catch (error) {
      if ((error as Error)?.message === "A dataset already exists with the same name.") {
        const dataset = await this.getDataset(name);
        await dataset.__internal_api__sync_hashes__();
        return dataset;
      } else {
        throw error;
      }
    }
  };

  public createDataset = async (name: string, description?: string) => {
    logger.debug(`Creating dataset named '${name}'`);
    const datasetId = generateId();

    try {
      await this.api.datasets.createDataset({
        id: datasetId,
        name: name,
        description: description,
      });
    } catch (error) {
      if ((error as OpikApiError)?.statusCode === 409) {
        throw new Error(`A dataset already exists with the same name.`);
      } else {
        throw error;
      }
    }
    
    return new Dataset(datasetId, name, description, this.api);
  };

  public getDataset = async (name: string) => {
    logger.debug(`Getting dataset named '${name}'`);
    let APIDataset: DatasetPublic;

    try {
      APIDataset = await this.api.datasets.getDatasetByIdentifier({
        datasetName: name,
      });
    } catch (error) {
      if ((error as OpikApiError)?.statusCode === 404) {
        throw new Error(`Could not find dataset named '${name}'.`);
      } else {
        throw error;
      }
    }
    
    const dataset = new Dataset(
      APIDataset.id!,
      APIDataset.name,
      APIDataset.description,
      this.api,
    );
    
    await dataset.__internal_api__sync_hashes__();
    return dataset;
  };

  public deleteDataset = async (name: string) => {
    logger.debug(`Deleting dataset named '${name}'`);
    await this.api.datasets.deleteDatasetByName({ datasetName: name });
    logger.debug(`Deleted dataset named '${name}'`);
  };

  public flush = async () => {
    logger.debug("Starting flush operation");
    try {
      await this.traceBatchQueue.flush();
      await this.spanBatchQueue.flush();
      await this.traceFeedbackScoresBatchQueue.flush();
      await this.spanFeedbackScoresBatchQueue.flush();
      logger.info("Successfully flushed all data to Opik");
    } catch (error) {
      logger.error("Error during flush operation:", {
        error: error instanceof Error ? error.message : error,
      });
    }
  };
}
