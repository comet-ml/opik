import { SavedTrace } from "@/tracer/Trace";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

export class TraceBatchQueue extends BatchQueue<SavedTrace> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "TraceBatchQueue",
    });
  }

  protected getId(entity: SavedTrace) {
    return entity.id;
  }

  protected async createEntities(traces: SavedTrace[]) {
    await this.api.traces.createTraces({ traces }, this.api.requestOptions);
  }

  protected async getEntity(id: string) {
    return (await this.api.traces.getTraceById(
      id,
      {},
      this.api.requestOptions
    )) as SavedTrace;
  }

  protected async updateEntity(id: string, updates: Partial<SavedTrace>) {
    await this.api.traces.updateTrace(id, updates, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    await this.api.traces.deleteTraces({ ids }, this.api.requestOptions);
  }
}
