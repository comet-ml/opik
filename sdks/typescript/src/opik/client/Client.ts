import { loadConfig, OpikConfig } from "@/config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";

export type SavedTrace = ITrace & { id: string };

export class OpikClient {
  private apiClient: OpikApiClient;
  private config: OpikConfig;

  constructor(explicitConfig?: Partial<OpikConfig>) {
    this.config = loadConfig(explicitConfig);
    this.apiClient = new OpikApiClient({
      environment: this.config.host,
    });
  }

  public trace = async (trace: ITrace) => {
    const traceWithId = {
      id: "test",
      ...trace,
    };

    await this.apiClient.traces.createTrace(trace);

    return new Trace(traceWithId, this.apiClient);
  };
}
