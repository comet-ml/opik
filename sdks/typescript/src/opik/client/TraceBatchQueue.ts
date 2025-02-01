import { OpikApiClient } from "@/rest_api/Client";
import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly apiClient: OpikApiClient,
    delay?: number
  ) {
    super({ delay, name: "TraceBatchQueue" });
  }

  protected async createEntities(traces: SavedTrace[]) {
    await this.apiClient.traces.createTraces({ traces });
  }

  protected async getEntity(id: string) {
    return (await this.apiClient.traces.getTraceById(id)) as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    await this.apiClient.traces.updateTrace(id, updates);
  }

  protected async deleteEntities(ids: string[]) {
    await this.apiClient.traces.deleteTraces({ ids });
  }
}
