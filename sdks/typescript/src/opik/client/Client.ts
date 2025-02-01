import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";
import { v7 as uuid } from "uuid";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

export class OpikClient {
  public api: OpikApiClient;
  public config: OpikConfig;
  public spanBatchQueue: SpanBatchQueue;
  public traceBatchQueue: TraceBatchQueue;

  constructor(explicitConfig?: Partial<OpikConfig>) {
    this.config = loadConfig(explicitConfig);
    this.api = new OpikApiClient({
      apiKey: this.config.apiKey,
      environment: this.config.host,
      workspaceName: this.config.workspaceName,
    });

    this.spanBatchQueue = new SpanBatchQueue(this.api);
    this.traceBatchQueue = new TraceBatchQueue(this.api);
  }

  public trace = (traceData: TraceData) => {
    const projectName = traceData.projectName ?? this.config.projectName;
    const trace = new Trace(
      {
        id: uuid(),
        startTime: new Date(),
        ...traceData,
        projectName,
      },
      this
    );

    this.traceBatchQueue.create(trace.data);

    return trace;
  };

  public flush = async () => {
    await this.traceBatchQueue.flush();
    await this.spanBatchQueue.flush();
  };
}
