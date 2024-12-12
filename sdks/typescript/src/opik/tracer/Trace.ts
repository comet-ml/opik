import { OpikApiClient } from "@/rest_api";
import type {
  Span as ISpan,
  Trace as ITrace,
  TraceUpdate,
} from "@/rest_api/api";
import { v7 as uuid } from "uuid";
import { SavedSpan, Span } from "./Span";

export interface SavedTrace extends ITrace {
  id: string;
}

export class Trace {
  private spans: Span[] = [];

  constructor(
    private data: SavedTrace,
    private apiClient: OpikApiClient
  ) {}

  public end = async () => {
    await this.update({ endTime: new Date() });
  };

  public span = async (spanData: Omit<ISpan, "traceId">) => {
    const spanWithId: SavedSpan = {
      id: uuid(),
      ...spanData,
      traceId: this.data.id,
    };

    await this.apiClient.spans.createSpans({ spans: [spanWithId] }).asRaw();

    const span = new Span(spanWithId, this.apiClient);
    this.spans.push(span);
    return span;
  };

  public update = async (
    updates: Omit<TraceUpdate, "projectId" | "projectName">
  ) => {
    await this.apiClient.traces.updateTrace(this.data.id, updates).asRaw();
    this.data = { ...this.data, ...updates };
  };
}
