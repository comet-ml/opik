import { loadConfig, OpikConfig } from "@/config/Config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { SavedTrace, Trace } from "@/tracer/Trace";
import { v7 as uuid } from "uuid";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

export class OpikClient {
  private apiClient: OpikApiClient;
  private config: OpikConfig;

  constructor(explicitConfig?: Partial<OpikConfig>) {
    this.config = loadConfig(explicitConfig);
    this.apiClient = new OpikApiClient({
      environment: this.config.host,
    });
  }

  public trace = async (trace: TraceData) => {
    const traceWithId: SavedTrace = {
      id: uuid(),
      startTime: new Date(),
      ...trace,
    };

    await this.apiClient.traces.createTrace(traceWithId).asRaw();

    return new Trace(traceWithId, this.apiClient);
  };
}
