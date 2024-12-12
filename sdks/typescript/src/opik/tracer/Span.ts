import { OpikApiClient } from "@/rest_api";
import type { Span as ISpan, SpanUpdate } from "@/rest_api/api";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  constructor(
    private data: SavedSpan,
    private apiClient: OpikApiClient
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
    await this.apiClient.spans
      .updateSpan(this.data.id, { traceId: this.data.traceId, ...updates })
      .asRaw();

    this.data = { ...this.data, ...updates };
  };
}
