import { OpikApiClient } from "@/rest_api/Client";
import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly apiClient: OpikApiClient,
    delay?: number
  ) {
    super({ delay });
  }

  protected async createEntities(traces: SavedTrace[]) {
    await this.apiClient.traces.createTraces({ traces }).asRaw();
  }

  protected async getEntity(id: string) {
    const { data } = await this.apiClient.traces.getTraceById(id).asRaw();

    return data as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    await this.apiClient.traces.updateTrace(id, updates).asRaw();
  }

  protected async deleteEntities(ids: string[]) {
    await this.apiClient.traces.deleteTraces({ ids }).asRaw();
  }
}
