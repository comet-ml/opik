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
    await this.opik.apiClient.spans
      .updateSpan(this.data.id, {
        projectName: this.data.projectName ?? this.opik.config.projectName,
        traceId: this.data.traceId,
        ...updates,
      })
      .asRaw();

    this.data = { ...this.data, ...updates };
  };
}
