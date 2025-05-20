import { OpikClient } from "@/client/Client";
import type { Span as ISpan, SpanUpdate } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  private childSpans: Span[] = [];

  constructor(
    public data: SavedSpan,
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
    this.opik.spanFeedbackScoresBatchQueue.create({
      ...score,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      id: this.data.id,
      source: "sdk",
    });
  };

  public update = (
    updates: Omit<
      SpanUpdate,
      "traceId" | "parentSpanId" | "projectId" | "projectName"
    >
  ) => {
    const spanUpdates = {
      parentSpanId: this.data.parentSpanId,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      traceId: this.data.traceId,
      ...updates,
    };

    this.opik.spanBatchQueue.update(this.data.id, spanUpdates);

    this.data = { ...this.data, ...updates };

    return this;
  };

  public span = (
    spanData: Omit<
      ISpan,
      | "startTime"
      | "traceId"
      | "parentSpanId"
      | "projectId"
      | "projectName"
      | "id"
    > & {
      startTime?: Date;
    }
  ) => {
    const projectName = this.data.projectName ?? this.opik.config.projectName;

    const spanWithId: SavedSpan = {
      id: generateId(),
      startTime: new Date(),
      ...spanData,
      projectName,
      traceId: this.data.traceId,
      parentSpanId: this.data.id,
    };

    this.opik.spanBatchQueue.create(spanWithId);

    const span = new Span(spanWithId, this.opik);
    this.childSpans.push(span);

    return span;
  };
}
