import { OpikApiClient } from "@/rest_api/Client";
import { BatchQueue } from "./BatchQueue";
import { SavedTrace, Trace } from "@/tracer/Trace";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly apiClient: OpikApiClient,
    delay?: number
  ) {
    super(delay);
  }

  protected async createEntities(entities: SavedTrace[]) {
    await this.apiClient.traces.createTraces({ traces: entities });
  }

  protected async getEntity(id: string) {
    const { data } = await this.apiClient.traces.getTraceById(id).asRaw();

    return data as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    return this.apiClient.traces.updateTrace(id, updates);
  }

  protected async deleteEntities(ids: string[]) {
    return this.apiClient.traces.deleteTraces({ ids });
  }
}
