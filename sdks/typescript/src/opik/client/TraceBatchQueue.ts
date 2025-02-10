import { OpikApiClient } from "@/rest_api/Client";
import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly api: OpikApiClient,
    delay?: number
  ) {
    super({ delay, name: "TraceBatchQueue" });
  }

  protected async createEntities(traces: SavedTrace[]) {
    await this.api.traces.createTraces({ traces });
  }

  protected async getEntity(id: string) {
    return (await this.api.traces.getTraceById(id)) as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    await this.api.traces.updateTrace(id, updates);
  }

  protected async deleteEntities(ids: string[]) {
    await this.api.traces.deleteTraces({ ids });
  }
}
