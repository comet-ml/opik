import type { AttributeValue, Tracer } from "@opentelemetry/api";
import { SpanStatusCode } from "@opentelemetry/api";
import type { ExportResultCode } from "@opentelemetry/core";
import type { NodeSDKConfiguration } from "@opentelemetry/sdk-node";
import { Opik, generateId, logger } from "opik";
import {
  getSpanInput,
  getSpanMetadata,
  getSpanOutput,
  getSpanType,
  getSpanUsage,
  getThreadId,
} from "./attributes";

/** Shape used for error_info when creating traces/spans; matches Opik API ErrorInfo. */
type ErrorInfo = {
  exceptionType: string;
  message?: string;
  traceback: string;
};

type SpanExporter = NodeSDKConfiguration["traceExporter"];
type ExportFunction = SpanExporter["export"];
type ReadableSpan = Parameters<ExportFunction>[0][0];

type TelemetrySettings = {
  isEnabled?: boolean;
  recordInputs?: boolean;
  recordOutputs?: boolean;
  functionId?: string;
  metadata?: Record<string, AttributeValue>;
  tracer?: Tracer;
};

type OpikExporterSettings = TelemetrySettings & {
  name?: string;
};

type OpikExporterOptions = {
  client?: Opik;
  tags?: string[];
  metadata?: Record<string, AttributeValue>;
  threadId?: string;
  /**
   * How long (ms) to retain a trace's accumulated spans/ids after its last
   * activity before pruning them, to bound memory in long-running processes.
   * A trace idle longer than this is assumed complete. Defaults to 10 minutes.
   */
  traceTtlMs?: number;
};

const aiSDKClient = new Opik();

// A turn's spans arrive across export batches; we keep accumulating until a
// trace has been idle this long, then prune it. Generous enough not to drop
// in-flight turns (durable eve sessions can pause between turns).
const DEFAULT_TRACE_TTL_MS = 10 * 60 * 1000;

// Instrumentation scopes we ingest. AI SDK v4/v5/v6 emit scope "ai"; AI SDK v7
// (and frameworks built on it, such as Vercel eve) emit the OpenTelemetry GenAI
// scope "gen_ai" plus an "eve"-scoped turn root span. Everything else (e.g.
// "workflow", "@vercel/otel/fetch") is framework noise and stays excluded.
const SUPPORTED_SCOPES = new Set(["ai", "gen_ai", "eve"]);

type SpanTiming = { id: string; start: number; end: number; duration: number };

export class OpikExporter implements SpanExporter {
  // <otelTraceId, <otelSpanId, ReadableSpan>>. A turn's spans arrive across
  // several export batches (the BatchSpanProcessor flushes as spans finish), so
  // we accumulate them and re-derive the whole trace on every batch.
  private readonly otelSpansByTrace = new Map<
    string,
    Map<string, ReadableSpan>
  >();
  // Stable Opik ids keyed by OTel id. Re-sending an entity with the same id is
  // an upsert, so revising a trace/span never needs an update() call.
  private readonly traceIds = new Map<string, string>();
  private readonly spanIds = new Map<string, string>();
  // Wall-clock of the last export that touched each otelTraceId, for TTL pruning.
  private readonly lastSeenByTrace = new Map<string, number>();

  private readonly client: Opik;
  private readonly tags: string[];
  private readonly metadata: Record<string, AttributeValue>;
  private readonly threadId?: string;
  private readonly traceTtlMs: number;

  constructor({
    client = aiSDKClient,
    tags = [],
    metadata = {},
    threadId,
    traceTtlMs = DEFAULT_TRACE_TTL_MS,
  }: OpikExporterOptions = {}) {
    this.client = client;
    this.tags = [...tags];
    this.metadata = { ...metadata };
    this.threadId = threadId;
    this.traceTtlMs = traceTtlMs;
  }

  private opikTraceId = (otelTraceId: string): string => {
    let id = this.traceIds.get(otelTraceId);

    if (!id) {
      id = generateId();
      this.traceIds.set(otelTraceId, id);
    }

    return id;
  };

  private opikSpanId = (otelSpanId: string): string => {
    let id = this.spanIds.get(otelSpanId);

    if (!id) {
      id = generateId();
      this.spanIds.set(otelSpanId, id);
    }

    return id;
  };

  // Drop accumulated spans and id mappings for traces idle longer than the TTL,
  // so a long-running exporter doesn't retain every trace for the process'
  // lifetime. A trace re-derives from all its spans on each batch, so we only
  // prune once it has been quiet long enough to be considered complete.
  private pruneStaleTraces = (now: number): void => {
    for (const [otelTraceId, lastSeen] of this.lastSeenByTrace) {
      if (now - lastSeen <= this.traceTtlMs) {
        continue;
      }

      const spans = this.otelSpansByTrace.get(otelTraceId);
      if (spans) {
        for (const otelSpanId of spans.keys()) {
          this.spanIds.delete(otelSpanId);
        }
      }

      this.otelSpansByTrace.delete(otelTraceId);
      this.traceIds.delete(otelTraceId);
      this.lastSeenByTrace.delete(otelTraceId);
    }
  };

  private getErrorInfo = (otelSpan: ReadableSpan): ErrorInfo | undefined => {
    if (otelSpan.status.code !== SpanStatusCode.ERROR) {
      return undefined;
    }

    const exceptionEvent = otelSpan.events.find(
      (event) => event.name === "exception"
    );

    if (!exceptionEvent) {
      return {
        exceptionType: "Error",
        message: otelSpan.status.message || "An error occurred",
        traceback: "",
      };
    }

    const { attributes } = exceptionEvent;
    const exceptionType = attributes?.["exception.type"]?.toString() || "Error";
    const message = attributes?.["exception.message"]?.toString();
    const traceback = attributes?.["exception.stacktrace"]?.toString() || "";

    return { exceptionType, message, traceback };
  };

  // eve groups multi-turn conversations under a session id that may live on any
  // span of the turn, so scan the whole group before the constructor fallback.
  private resolveThreadId = (
    otelSpans: ReadableSpan[]
  ): string | undefined => {
    for (const otelSpan of otelSpans) {
      const threadId = getThreadId(otelSpan.attributes);

      if (threadId != null) {
        return threadId;
      }
    }

    return this.threadId;
  };

  shutdown = async (): Promise<void> => {
    await this.client.flush();
  };

  forceFlush = async (): Promise<void> => {
    await this.client.flush();
  };

  export: ExportFunction = async (allOtelSpans, resultCallback) => {
    const aiSDKOtelSpans = allOtelSpans.filter((span: ReadableSpan) =>
      SUPPORTED_SCOPES.has(getInstrumentationScopeName(span) ?? "")
    );
    const diffCount = allOtelSpans.length - aiSDKOtelSpans.length;

    if (diffCount > 0) {
      logger.debug(`Ignored ${diffCount} non-AI SDK spans`);
    }

    if (aiSDKOtelSpans.length === 0) {
      logger.debug("No AI SDK spans found");

      const code: ExportResultCode.SUCCESS = 0;
      resultCallback({ code });

      return;
    }

    logger.debug("Exporting spans", aiSDKOtelSpans);

    const affectedTraceIds = new Set<string>();

    for (const otelSpan of aiSDKOtelSpans) {
      const otelTraceId = otelSpan.spanContext().traceId;
      affectedTraceIds.add(otelTraceId);

      let spans = this.otelSpansByTrace.get(otelTraceId);

      if (!spans) {
        spans = new Map();
        this.otelSpansByTrace.set(otelTraceId, spans);
      }

      spans.set(otelSpan.spanContext().spanId, otelSpan);
    }

    const now = Date.now();
    for (const otelTraceId of affectedTraceIds) {
      this.lastSeenByTrace.set(otelTraceId, now);
      this.exportTrace(otelTraceId);
    }

    this.pruneStaleTraces(now);

    try {
      await this.client.flush();

      const code: ExportResultCode.SUCCESS = 0;

      resultCallback({ code });
    } catch (error) {
      logger.error("Error exporting spans", error);

      const code: ExportResultCode.FAILED = 1;

      resultCallback({
        code,
        error: error instanceof Error ? error : new Error("Unknown error"),
      });
    }
  };

  // Re-derive and (re)send a whole trace and its spans from every span seen for
  // it so far. Uses create-only operations: the Opik ids are stable, so each
  // send upserts the previous one — no update()/end() calls.
  private exportTrace(otelTraceId: string): void {
    const otelSpans = [...(this.otelSpansByTrace.get(otelTraceId)?.values() ?? [])];

    if (otelSpans.length === 0) {
      return;
    }

    const traceId = this.opikTraceId(otelTraceId);
    const projectName = this.client.config.projectName;
    const parentOf = resolveParents(otelSpans);

    // The root span becomes the Opik trace itself; every other span is a child.
    const rootOtelSpan = pickRootSpan(otelSpans, parentOf);
    const rootOtelSpanId = rootOtelSpan.spanContext().spanId;

    // Prefer the root span's own input/output — this preserves AI SDK v4/v5/v6,
    // where the root model call carries them. Fall back to the model-call spans
    // when the root is an orchestration span that carries none (e.g. eve's
    // `ai.eve.turn`). Token usage is intentionally NOT set on the trace: it
    // lives only on LLM spans, and the trace total is aggregated from them.
    const llmSpans = sortByStart(
      otelSpans.filter((otelSpan) => getSpanType(otelSpan.attributes) === "llm")
    );

    const input = preferRoot(getSpanInput(rootOtelSpan.attributes), () =>
      firstNonEmpty(llmSpans, getSpanInput)
    );
    const output = preferRoot(getSpanOutput(rootOtelSpan.attributes), () =>
      lastNonEmpty(llmSpans, getSpanOutput)
    );

    const errorInfo =
      this.getErrorInfo(rootOtelSpan) ?? firstError(otelSpans, this.getErrorInfo);

    this.client.traceBatchQueue.create({
      id: traceId,
      name:
        rootOtelSpan.attributes["ai.telemetry.metadata.traceName"]?.toString() ??
        rootOtelSpan.name,
      // Span the whole turn even when the root span's own window is shorter.
      startTime: new Date(Math.min(...otelSpans.map(startMs))),
      endTime: new Date(Math.max(...otelSpans.map(endMs))),
      input,
      output,
      metadata: { ...mergeMetadata(otelSpans), ...this.metadata },
      tags: this.tags,
      threadId: this.resolveThreadId(otelSpans),
      projectName,
      ...(errorInfo && { errorInfo }),
    });

    for (const otelSpan of otelSpans) {
      const otelSpanId = otelSpan.spanContext().spanId;

      if (otelSpanId === rootOtelSpanId) {
        continue;
      }

      // Spans whose parent is the root attach directly to the trace.
      const parentOtelSpanId = parentOf.get(otelSpanId);
      const parentSpanId =
        parentOtelSpanId != null && parentOtelSpanId !== rootOtelSpanId
          ? this.opikSpanId(parentOtelSpanId)
          : undefined;
      const spanErrorInfo = this.getErrorInfo(otelSpan);
      const spanType = getSpanType(otelSpan.attributes);

      this.client.spanBatchQueue.create({
        id: this.opikSpanId(otelSpanId),
        traceId,
        ...(parentSpanId ? { parentSpanId } : {}),
        name: otelSpan.name,
        type: spanType,
        startTime: new Date(startMs(otelSpan)),
        endTime: new Date(endMs(otelSpan)),
        input: getSpanInput(otelSpan.attributes),
        output: getSpanOutput(otelSpan.attributes),
        metadata: getSpanMetadata(otelSpan.attributes),
        // Only LLM spans carry token usage. eve emits the same usage on both
        // the agent/step wrapper and the underlying model-call span, so keeping
        // it on non-LLM spans would double-count when the trace usage is
        // aggregated across spans.
        ...(spanType === "llm"
          ? { usage: getSpanUsage(otelSpan.attributes) }
          : {}),
        projectName,
        ...(spanErrorInfo && { errorInfo: spanErrorInfo }),
      });
    }
  }

  static getSettings(settings: OpikExporterSettings): TelemetrySettings {
    const metadata = { ...settings.metadata };

    if (settings.name) {
      metadata.traceName = settings.name;
    }

    return {
      isEnabled: settings.isEnabled ?? true,
      recordInputs: settings.recordInputs ?? true,
      recordOutputs: settings.recordOutputs ?? true,
      functionId: settings.functionId,
      metadata,
    };
  }
}

// Get instrumentation scope name with fallback for OpenTelemetry v1 compatibility.
// OTel v1 (used by @vercel/otel) uses `instrumentationLibrary` while
// OTel v2 uses `instrumentationScope`.
function getInstrumentationScopeName(span: ReadableSpan): string | undefined {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const s = span as any;
  return (
    s.instrumentationScope?.name ?? s.instrumentationLibrary?.name ?? undefined
  );
}

// Convert hrTime ([seconds, nanoseconds]) to milliseconds.
function hrTimeToMilliseconds(hrTime: [number, number]): number {
  return hrTime[0] * 1e3 + hrTime[1] / 1e6;
}

function startMs(otelSpan: ReadableSpan): number {
  return hrTimeToMilliseconds(otelSpan.startTime);
}

function endMs(otelSpan: ReadableSpan): number {
  return hrTimeToMilliseconds(otelSpan.endTime);
}

function sortByStart(otelSpans: ReadableSpan[]): ReadableSpan[] {
  return [...otelSpans].sort((a, b) => startMs(a) - startMs(b));
}

// Frameworks built on the AI SDK (eve, Vercel Workflow) do not emit OTel
// parent-child links, so reconstruct the hierarchy from time containment: a
// span's parent is the smallest span that fully contains its time window.
// Requiring a strictly longer parent keeps the relation acyclic.
function computeParentsByContainment(
  otelSpans: ReadableSpan[]
): Map<string, string | undefined> {
  const timings: SpanTiming[] = otelSpans.map((otelSpan) => {
    const start = startMs(otelSpan);
    const end = endMs(otelSpan);

    return { id: otelSpan.spanContext().spanId, start, end, duration: end - start };
  });

  const parentOf = new Map<string, string | undefined>();

  for (const span of timings) {
    let parent: SpanTiming | undefined;

    for (const candidate of timings) {
      if (candidate.id === span.id) {
        continue;
      }

      const contains =
        candidate.start <= span.start &&
        candidate.end >= span.end &&
        candidate.duration > span.duration;

      if (contains && (!parent || candidate.duration < parent.duration)) {
        parent = candidate;
      }
    }

    parentOf.set(span.id, parent?.id);
  }

  return parentOf;
}

function firstNonEmpty<T extends object>(
  otelSpans: ReadableSpan[],
  extract: (attributes: ReadableSpan["attributes"]) => T
): T {
  for (const otelSpan of otelSpans) {
    const value = extract(otelSpan.attributes);

    if (Object.keys(value).length > 0) {
      return value;
    }
  }

  return {} as T;
}

function lastNonEmpty<T extends object>(
  otelSpans: ReadableSpan[],
  extract: (attributes: ReadableSpan["attributes"]) => T
): T {
  for (let index = otelSpans.length - 1; index >= 0; index--) {
    const value = extract(otelSpans[index].attributes);

    if (Object.keys(value).length > 0) {
      return value;
    }
  }

  return {} as T;
}

function mergeMetadata(otelSpans: ReadableSpan[]): Record<string, unknown> {
  let metadata: Record<string, unknown> = {};

  for (const otelSpan of otelSpans) {
    metadata = { ...metadata, ...getSpanMetadata(otelSpan.attributes) };
  }

  return metadata;
}

// Each span's parent: a real OTel parent link when the parent is one of our
// spans (AI SDK v4/v5/v6), otherwise the time-containment parent (eve, which
// emits no parent links). The result is acyclic.
function resolveParents(
  otelSpans: ReadableSpan[]
): Map<string, string | undefined> {
  const keptIds = new Set(
    otelSpans.map((otelSpan) => otelSpan.spanContext().spanId)
  );
  const containment = computeParentsByContainment(otelSpans);
  const parentOf = new Map<string, string | undefined>();

  for (const otelSpan of otelSpans) {
    const spanId = otelSpan.spanContext().spanId;
    const realParentId = otelSpan.parentSpanContext?.spanId;

    parentOf.set(
      spanId,
      realParentId != null && keptIds.has(realParentId)
        ? realParentId
        : containment.get(spanId)
    );
  }

  return parentOf;
}

// The trace's root span: an outermost (parent-less) span, earliest by start.
function pickRootSpan(
  otelSpans: ReadableSpan[],
  parentOf: Map<string, string | undefined>
): ReadableSpan {
  const topLevel = otelSpans.filter(
    (otelSpan) => parentOf.get(otelSpan.spanContext().spanId) == null
  );

  return sortByStart(topLevel.length > 0 ? topLevel : otelSpans)[0];
}

function preferRoot<T extends object>(rootValue: T, fallback: () => T): T {
  return Object.keys(rootValue).length > 0 ? rootValue : fallback();
}

function firstError(
  otelSpans: ReadableSpan[],
  getErrorInfo: (otelSpan: ReadableSpan) => ErrorInfo | undefined
): ErrorInfo | undefined {
  for (const otelSpan of sortByStart(otelSpans)) {
    const errorInfo = getErrorInfo(otelSpan);

    if (errorInfo) {
      return errorInfo;
    }
  }

  return undefined;
}
