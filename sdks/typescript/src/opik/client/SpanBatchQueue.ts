import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number
  ) {
    super({
      delay,
      enableCreateBatch: true,
      enableUpdateBatch: true,
      enableDeleteBatch: true,
      name: "SpanBatchQueue",
    });
  }

  protected getId(entity: SavedSpan) {
    return entity.id;
  }

  protected async createEntities(spans: SavedSpan[]) {
    await this.api.spans.createSpans({ spans }, this.api.requestOptions);
  }

  protected async getEntity(id: string) {
    return (await this.api.spans.getSpanById(
      id,
      {},
      this.api.requestOptions
    )) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    await this.api.spans.updateSpan(id, updates, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.api.spans.deleteSpanById(id, this.api.requestOptions);
    }
  }
}
