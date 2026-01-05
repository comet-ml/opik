import { OpikClient } from "@/client/Client";
import type { Span as ISpan, Trace as ITrace } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { SavedSpan, Span } from "./Span";
import type { TraceUpdateData } from "./types";
import { UpdateService } from "./UpdateService";

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

  public score = (score: {
    name: string;
    categoryName?: string;
    value: number;
    reason?: string;
  }) => {
    this.opik.traceFeedbackScoresBatchQueue.create({
      ...score,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      id: this.data.id,
      source: "sdk",
    });
  };

  public span = (spanData: SpanData) => {
    const projectName =
      this.data.projectName ??
      spanData.projectName ??
      this.opik.config.projectName;

    const spanWithId: SavedSpan = {
      id: generateId(),
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

  public update = (updates: TraceUpdateData) => {
    const processedUpdates = UpdateService.processTraceUpdate(
      updates,
      this.data.metadata
    );

    const traceUpdates = {
      projectName: this.data.projectName ?? this.opik.config.projectName,
      ...processedUpdates,
    };

    this.opik.traceBatchQueue.update(this.data.id, traceUpdates);
    this.data = { ...this.data, ...traceUpdates };

    return this;
  };
}
