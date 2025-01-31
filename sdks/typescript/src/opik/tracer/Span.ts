import { OpikClient } from "@/client/Client";
import type { Span as ISpan, SpanUpdate } from "@/rest_api/api";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  constructor(
    private data: SavedSpan,
    private opik: OpikClient
  ) {}

  public end = async () => {
    await this.update({ endTime: new Date() });
  };

  public update = async (
    updates: Omit<
      SpanUpdate,
      "traceId" | "parentSpanId" | "projectId" | "projectName"
    >
  ) => {
    const spanUpdates = {
      projectName: this.data.projectName ?? this.opik.config.projectName,
      traceId: this.data.traceId,
      ...updates,
    };

    this.opik.spanBatchQueue.update(this.data.id, spanUpdates);

    this.data = { ...this.data, ...updates };
  };
}
