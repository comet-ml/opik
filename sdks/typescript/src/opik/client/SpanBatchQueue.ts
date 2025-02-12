import { OpikApiClient } from "@/rest_api/Client";
import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly api: OpikApiClient,
    delay?: number
  ) {
    super({ delay, enableDeleteBatch: false, name: "SpanBatchQueue" });
  }

  protected getId(entity: SavedSpan) {
    return entity.id;
  }

  protected async createEntities(spans: SavedSpan[]) {
    await this.api.spans.createSpans({ spans });
  }

  protected async getEntity(id: string) {
    return (await this.api.spans.getSpanById(id)) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    await this.api.spans.updateSpan(id, updates);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.api.spans.deleteSpanById(id);
    }
  }
}
