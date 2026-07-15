import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { truncateSpanIfNeeded } from "./spanTruncation";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly api: OpikApiClientTemp,
    delay?: number,
    private readonly maxSpanPayloadSizeMb?: number
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
    const payload = spans.map((span) =>
      truncateSpanIfNeeded(span, this.maxSpanPayloadSizeMb ?? 0, span.id)
    );
    await this.api.spans.createSpans({ spans: payload }, this.api.requestOptions);
  }

  protected async getEntity(id: string) {
    return (await this.api.spans.getSpanById(
      id,
      {},
      this.api.requestOptions
    )) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    const body = truncateSpanIfNeeded(updates, this.maxSpanPayloadSizeMb ?? 0, id);
    await this.api.spans.updateSpan(id, { body }, this.api.requestOptions);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.api.spans.deleteSpanById(id, this.api.requestOptions);
    }
  }
}
