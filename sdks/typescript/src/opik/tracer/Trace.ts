import { OpikClient } from "@/client/Client";
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

interface SpanData extends Omit<ISpan, "startTime" | "traceId"> {
  startTime?: Date;
}

export class Trace {
  private spans: Span[] = [];

  constructor(
    public data: SavedTrace,
    private opik: OpikClient
  ) {}

  public end = () => {
    return this.update({ endTime: new Date() });
  };

  public span = (spanData: SpanData) => {
    const projectName =
      this.data.projectName ??
      spanData.projectName ??
      this.opik.config.projectName;

    const spanWithId: SavedSpan = {
      id: uuid(),
      startTime: new Date(),
      ...spanData,
      projectName,
      traceId: this.data.id,
    };

    this.opik.spanBatchQueue.create(spanWithId);

    const span = new Span(spanWithId, this.opik);
    this.spans.push(span);
    return span;
  };

  public update = (updates: Omit<TraceUpdate, "projectId">) => {
    const traceUpdates = {
      projectName: this.data.projectName ?? this.opik.config.projectName,
      ...updates,
    };

    this.opik.traceBatchQueue.update(this.data.id, traceUpdates);
    this.data = { ...this.data, ...traceUpdates };

    return this;
  };
}
