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
    const groupedSpans = groupSpansByDependency(spans);
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

function groupSpansByDependency(spans: SavedSpan[]): SavedSpan[][] {
  const groups: SavedSpan[][] = [];
  const executed = new Set<string>();
  let remaining = [...spans];

  while (remaining.length > 0) {
    const executable = remaining.filter((span) => {
      if (!span.parentSpanId) {
        return true;
      }

      const parentExists = spans.some((s) => s.id === span.parentSpanId);
      if (!parentExists) {
        return true;
      }

      return executed.has(span.parentSpanId);
    });

    if (executable.length === 0) {
      // throw new Error("Cycle detected or unsatisfied dependency among spans.");
      break;
    }

    groups.push(executable);

    for (const span of executable) {
      executed.add(span.id);
    }

    remaining = remaining.filter((span) => !executed.has(span.id));
  }

  return groups;
}
