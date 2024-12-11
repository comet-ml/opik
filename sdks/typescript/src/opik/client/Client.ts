import { loadConfig, OpikConfig } from "@/config";
import { OpikApiClient } from "@/rest_api";
import type { Trace as ITrace } from "@/rest_api/api";
import { Trace } from "@/tracer/Trace";

export class Client {
  private apiClient: OpikApiClient;
  private config: OpikConfig;

  constructor(explicitConfig?: Partial<OpikConfig>) {
    this.config = loadConfig(explicitConfig);
    this.apiClient = new OpikApiClient({
      environment: this.config.host,
    });
  }

  public trace = async (trace: ITrace) => {
    await this.apiClient.traces.createTrace(trace);

    return new Trace(trace, this.apiClient);
  };
}
