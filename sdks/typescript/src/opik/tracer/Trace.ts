import { OpikClient } from "@/client/Client";
import type { Span as ISpan, Trace as ITrace } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import { SavedSpan, Span } from "./Span";
import type { TraceUpdateData } from "./types";
import { UpdateService } from "./UpdateService";

export interface SavedTrace extends ITrace {
  id: string;
}

interface SpanData extends Omit<ISpan, "startTime" | "traceId" | "environment"> {
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

    // environment is trace-scoped; strip any caller-supplied value so JS/any callers
    // can't override it — the parent trace's environment is applied unconditionally below.
    const { environment: _env, ...spanDataWithoutEnv } = spanData as { environment?: string };
    if (_env !== undefined && _env !== this.data.environment) {
      logger.warn(
        `You are attempting to log data into a nested span under the environment "${_env}". ` +
          `However, the environment "${this.data.environment ?? ""}" from the parent trace will be used instead.`
      );
    }
    const spanWithId: SavedSpan = {
      id: generateId(),
      startTime: new Date(),
      source: this.data.source,
      ...spanDataWithoutEnv,
      projectName,
      traceId: this.data.id,
      ...(this.data.environment !== undefined
        ? { environment: this.data.environment }
        : {}),
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
