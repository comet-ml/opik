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

  public span = async (spanData: SpanData) => {
    const projectName = spanData.projectName ?? this.opik.config.projectName;
    const spanWithId: SavedSpan = {
      id: uuid(),
      startTime: new Date(),
      ...spanData,
      projectName,
      traceId: this.data.id,
    };

    await this.opik.loadProject(projectName);
    await this.opik.apiClient.spans
      .createSpans({ spans: [spanWithId] })
      .asRaw();

    const span = new Span(spanWithId, this.opik);
    this.spans.push(span);
    return span;
  };

  public update = (updates: Omit<TraceUpdate, "projectId">) => {
    /*
    await this.opik.apiClient.traces
      .updateTrace(this.data.id, {
        projectName: this.data.projectName ?? this.opik.config.projectName,
        ...updates,
      })
      .asRaw();
    */
    const requestUpdates = {
      projectName: this.data.projectName ?? this.opik.config.projectName,
      ...updates,
    };

    this.opik.traceBatchQueue.update(this.data.id, requestUpdates);
    this.data = { ...this.data, ...requestUpdates };

    return this;
  };
}
