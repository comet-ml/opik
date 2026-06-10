import { OpikClient } from "@/client/Client";
import type { Span as ISpan } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import type { SpanUpdateData } from "./types";
import { UpdateService } from "./UpdateService";

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

  public update = (updates: SpanUpdateData) => {
    const processedUpdates = UpdateService.processSpanUpdate(
      updates,
      this.data.metadata
    );

    const spanUpdates = {
      parentSpanId: this.data.parentSpanId,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      traceId: this.data.traceId,
      ...processedUpdates,
    };

    this.opik.spanBatchQueue.update(this.data.id, spanUpdates);

    this.data = { ...this.data, ...spanUpdates };

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
      | "environment"
    > & {
      startTime?: Date;
    }
  ) => {
    const projectName = this.data.projectName ?? this.opik.config.projectName;

    // environment is trace-scoped; strip any caller-supplied value so JS/any callers
    // can't override it — the parent span's environment is applied unconditionally below.
    const { environment: _env, ...spanDataWithoutEnv } = spanData as { environment?: string };
    if (_env !== undefined && _env !== this.data.environment) {
      logger.warn(
        `You are attempting to log data into a nested span under the environment "${_env}". ` +
          `However, the environment "${this.data.environment ?? ""}" from the parent span will be used instead.`
      );
    }
    const spanWithId: SavedSpan = {
      id: generateId(),
      startTime: new Date(),
      source: this.data.source,
      ...spanDataWithoutEnv,
      projectName,
      traceId: this.data.traceId,
      parentSpanId: this.data.id,
      ...(this.data.environment !== undefined
        ? { environment: this.data.environment }
        : {}),
    };

    this.opik.spanBatchQueue.create(spanWithId);

    const span = new Span(spanWithId, this.opik);
    this.childSpans.push(span);

    return span;
  };
}
