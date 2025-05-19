import type { AttributeValue, Tracer } from "@opentelemetry/api";
import type { ExportResultCode } from "@opentelemetry/core";
import type { NodeSDKConfiguration } from "@opentelemetry/sdk-node";
import type { Span, Trace } from "opik";
import { logger, Opik } from "opik";

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
};

const aiSDKClient = new Opik();

export class OpikExporter implements SpanExporter {
  // <otelTraceId, opikTrace>
  private readonly traces = new Map<string, Trace>();
  // <otelSpanId, opikSpan>
  private readonly spans = new Map<string, Span>();
  private readonly client: Opik;
  private readonly tags: string[];
  private readonly metadata: Record<string, AttributeValue>;

  constructor({
    client = aiSDKClient,
    tags = [],
    metadata = {},
  }: OpikExporterOptions = {}) {
    this.client = client;
    this.tags = [...tags];
    this.metadata = { ...metadata };
  }

  private getSpanInput = (otelSpan: ReadableSpan): Record<string, unknown> => {
    let input: Record<string, unknown> = {};
    const { attributes } = otelSpan;
    const attributeKeys = Object.keys(attributes);

    attributeKeys.forEach((key) => {
      if (key === "ai.prompt" || key === "gen_ai.request") {
        const parsedValue = tryParseJSON(attributes[key]);

        if (parsedValue) {
          input = { ...input, ...parsedValue };
        }
      }

      if (key.startsWith("ai.prompt.")) {
        const promptKey = key.replace("ai.prompt.", "");

        input[promptKey] = safeParseJson(attributes[key]);
      }

      if (key.startsWith("gen_ai.request.")) {
        const promptKey = key.replace("gen_ai.request.", "");

        input[promptKey] = safeParseJson(attributes[key]);
      }
    });

    if (Object.keys(input).length > 0) {
      return input;
    }

    if ("ai.toolCall.name" in attributes) {
      input.toolName = attributes["ai.toolCall.name"];
    }

    if ("ai.toolCall.args" in attributes) {
      input.args = attributes["ai.toolCall.args"];
    }

    return input;
  };

  private getSpanOutput = (otelSpan: ReadableSpan): Record<string, unknown> => {
    const { attributes } = otelSpan;

    if (attributes["ai.response.text"]) {
      return { text: attributes["ai.response.text"] };
    }

    if (attributes["ai.toolCall.result"]) {
      return { result: attributes["ai.toolCall.result"] };
    }

    if (attributes["ai.response.toolCalls"]) {
      return { toolCalls: safeParseJson(attributes["ai.response.toolCalls"]) };
    }

    return {};
  };

  private getSpanMetadata = (
    otelSpan: ReadableSpan
  ): Record<string, unknown> => {
    const { attributes } = otelSpan;
    const metadata: Record<string, unknown> = {};

    if (attributes["gen_ai.response.model"]) {
      metadata.model = attributes["gen_ai.response.model"];
    }

    if (attributes["gen_ai.system"]) {
      metadata.system = attributes["gen_ai.system"];
    }

    return metadata;
  };

  private getSpanUsage = (otelSpan: ReadableSpan): Record<string, number> => {
    const { attributes } = otelSpan;
    const usage: Record<string, number> = {};

    // prompt tokens
    if ("ai.usage.promptTokens" in attributes) {
      usage.prompt_tokens = attributes["ai.usage.promptTokens"] as number;
    }
    if ("gen_ai.usage.input_tokens" in attributes) {
      usage.prompt_tokens = attributes["gen_ai.usage.input_tokens"] as number;
    }

    // completion tokens
    if ("ai.usage.completionTokens" in attributes) {
      usage.completion_tokens = attributes[
        "ai.usage.completionTokens"
      ] as number;
    }
    if ("gen_ai.usage.output_tokens" in attributes) {
      usage.completion_tokens = attributes[
        "gen_ai.usage.output_tokens"
      ] as number;
    }

    if ("prompt_tokens" in usage || "completion_tokens" in usage) {
      usage.total_tokens =
        (usage.prompt_tokens || 0) + (usage.completion_tokens || 0);
    }

    return usage;
  };

  processSpan = ({
    otelSpan,
    parentSpan,
    trace,
  }: {
    otelSpan: ReadableSpan;
    parentSpan?: Span;
    trace: Trace;
  }): Span => {
    return trace.span({
      name: otelSpan.name,
      startTime: new Date(hrTimeToMilliseconds(otelSpan.startTime)),
      endTime: new Date(hrTimeToMilliseconds(otelSpan.endTime)),
      parentSpanId: parentSpan?.data.id,
      input: this.getSpanInput(otelSpan),
      output: this.getSpanOutput(otelSpan),
      metadata: this.getSpanMetadata(otelSpan),
      usage: this.getSpanUsage(otelSpan),
      type: "llm",
    });
  };

  shutdown = async (): Promise<void> => {
    await this.client.flush();
  };

  forceFlush = async (): Promise<void> => {
    await this.client.flush();
  };

  export: ExportFunction = async (allOtelSpans, resultCallback) => {
    const aiSDKOtelSpans = allOtelSpans.filter(
      (span) => span.instrumentationScope.name === "ai"
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

    const spanGroups = groupAndSortOtelSpans(aiSDKOtelSpans);

    logger.debug("Exporting spans", aiSDKOtelSpans);

    Object.entries(spanGroups).forEach(([otelTraceId, otelSpans]) => {
      const [rootOtelSpan, ...otherOtelSpans] = otelSpans;

      const trace = this.client.trace({
        startTime: new Date(hrTimeToMilliseconds(rootOtelSpan.startTime)),
        endTime: new Date(hrTimeToMilliseconds(rootOtelSpan.endTime)),
        name:
          rootOtelSpan.attributes[
            "ai.telemetry.metadata.traceName"
          ]?.toString() ?? rootOtelSpan.name,
        input: this.getSpanInput(rootOtelSpan),
        output: this.getSpanOutput(rootOtelSpan),
        metadata: { ...this.getSpanMetadata(rootOtelSpan), ...this.metadata },
        tags: this.tags,
        usage: this.getSpanUsage(rootOtelSpan),
      });

      this.traces.set(otelTraceId, trace);

      otherOtelSpans.forEach((otelSpan) => {
        const parentSpan = this.spans.get(
          otelSpan.parentSpanContext?.spanId ?? ""
        );
        const span = this.processSpan({ parentSpan, otelSpan, trace });

        this.spans.set(otelSpan.spanContext().spanId, span);
      });
    });

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

function groupAndSortOtelSpans(
  otelSpans: ReadableSpan[]
): Record<string, ReadableSpan[]> {
  const spanGroupsByTraceId: Record<string, ReadableSpan[]> = {};

  otelSpans.forEach((otelSpan) => {
    const context = otelSpan.spanContext();

    if (!spanGroupsByTraceId[context.traceId]) {
      spanGroupsByTraceId[context.traceId] = [];
    }

    spanGroupsByTraceId[context.traceId].push(otelSpan);
  });

  Object.entries(spanGroupsByTraceId).forEach(([traceId, otelSpans]) => {
    spanGroupsByTraceId[traceId] = sortSpansLevelOrder(otelSpans);
  });

  return spanGroupsByTraceId;
}

// Convert hrTime ([seconds, nanoseconds]) to milliseconds
function hrTimeToMilliseconds(hrTime: [number, number]) {
  return hrTime[0] * 1e3 + hrTime[1] / 1e6;
}

function safeParseJson(value: unknown): unknown {
  try {
    return JSON.parse(value as string) as Record<string, unknown>;
  } catch {
    return value;
  }
}

function tryParseJSON(input: unknown): Record<string, unknown> | undefined {
  if (typeof input !== "string") {
    return undefined;
  }

  try {
    const parsed = JSON.parse(input);

    if (
      parsed !== null &&
      typeof parsed === "object" &&
      !Array.isArray(parsed)
    ) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    return undefined;
  }

  return undefined;
}

function sortSpansLevelOrder(otelSpans: ReadableSpan[]): ReadableSpan[] {
  // (spanId, otelSpan)
  const idMap = new Map<string, ReadableSpan>();
  // (parentSpanId, [childrenOtelSpans])
  const childrenMap = new Map<string, ReadableSpan[]>();

  for (const otelSpan of otelSpans) {
    const { spanId } = otelSpan.spanContext();
    const parentSpanId = otelSpan.parentSpanContext?.spanId ?? "";
    idMap.set(spanId, otelSpan);

    if (parentSpanId) {
      if (!childrenMap.has(parentSpanId)) {
        childrenMap.set(parentSpanId, []);
      }
      childrenMap.get(parentSpanId)!.push(otelSpan);
    }
  }

  const roots = otelSpans.filter(
    (span) =>
      !span.parentSpanContext?.spanId ||
      !idMap.has(span.parentSpanContext.spanId)
  );

  const result: ReadableSpan[] = [];
  const queue: ReadableSpan[] = [...roots];

  while (queue.length > 0) {
    const current = queue.shift()!;
    result.push(current);
    const currentId = current.spanContext().spanId;
    const children = childrenMap.get(currentId) || [];
    queue.push(...children);
  }

  return result;
}
