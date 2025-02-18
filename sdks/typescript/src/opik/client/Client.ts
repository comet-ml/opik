import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrl } from "@/utils/url";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { SpanFeedbackScoresBatchQueue } from "./SpanFeedbackScoresBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";
import { TraceFeedbackScoresBatchQueue } from "./TraceFeedbackScoresBatchQueue";

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
      environment: this.config.host,
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

    clients.push(this);
  }

  private displayTraceLog = (projectName: string) => {
    if (projectName === this.lastProjectNameLogged || !this.config.host) {
      return;
    }

    const projectUrl = getProjectUrl({
      host: this.config.host,
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
