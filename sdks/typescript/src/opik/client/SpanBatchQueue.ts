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

  protected async createEntities(spans: SavedSpan[]) {
    const groupedSpans = groupSpansByParentSpanId(spans);
    for (const spansGroup of Object.values(groupedSpans)) {
      await this.api.spans.createSpans({
        spans: spansGroup,
      });
    }
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

function groupSpansByParentSpanId(spans: SavedSpan[]) {
  return spans.reduce(
    (acc, span) => {
      if (!acc[span.parentSpanId || "root"]) {
        acc[span.parentSpanId || "root"] = [];
      }
      acc[span.parentSpanId || "root"].push(span);
      return acc;
    },
    {} as Record<string, SavedSpan[]>
  );
}
