import { OpikApiClient } from "@/rest_api/Client";
import { SavedSpan } from "@/tracer/Span";
import { BatchQueue } from "./BatchQueue";

type SpanUpdate = Partial<SavedSpan> & { traceId: string };

export class SpanBatchQueue extends BatchQueue<SavedSpan> {
  constructor(
    private readonly apiClient: OpikApiClient,
    delay?: number
  ) {
    super({ delay, enableDeleteBatch: false, name: "SpanBatchQueue" });
  }

  protected async createEntities(spans: SavedSpan[]) {
    await this.apiClient.spans.createSpans({ spans });
  }

  protected async getEntity(id: string) {
    return (await this.apiClient.spans.getSpanById(id)) as SavedSpan;
  }

  protected async updateEntity(id: string, updates: SpanUpdate) {
    await this.apiClient.spans.updateSpan(id, updates);
  }

  protected async deleteEntities(ids: string[]) {
    for (const id of ids) {
      await this.apiClient.spans.deleteSpanById(id);
    }
  }
}
