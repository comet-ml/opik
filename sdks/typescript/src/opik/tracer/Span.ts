import { OpikClient } from "@/client/Client";
import type { Span as ISpan, SpanUpdate } from "@/rest_api/api";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  constructor(
    public data: SavedSpan,
    private opik: OpikClient
  ) {}

  public end = () => {
    return this.update({ endTime: new Date() });
  };

  public update = (
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

    return this;
  };
}
