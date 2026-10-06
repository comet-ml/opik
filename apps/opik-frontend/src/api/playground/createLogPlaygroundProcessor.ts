import { v7 } from "uuid";
import pick from "lodash/pick";

import {
  LogExperimentPromptVersion,
  LogErrorInfo,
  LogSpan,
  LogTrace,
  PromptLibraryMetadata,
} from "@/types/playground";

import { LOGS_SOURCE, SPAN_TYPE } from "@/types/traces";
import api, { SPANS_REST_ENDPOINT, TRACES_REST_ENDPOINT } from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { createBatchProcessor } from "@/lib/batches";
import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  OpenAiPipelineMode,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { ProviderMessageType } from "@/types/llm";
import { parseCompletionError, parseCompletionOutput } from "@/lib/playground";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";
import { sanitizeConfigForRequest } from "@/lib/modelUtils";

export interface LogQueueParams extends RunStreamingReturn {
  promptId: string;
  datasetItemId?: string;
  datasetName: string | null;
  datasetVersionId?: string;
  model: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
  providerMessages: ProviderMessageType[];
  // The prompt as authored, with {{variables}} intact. providerMessages is this
  // template rendered against one dataset item, so it describes a single run
  // rather than the experiment (OPIK-7965).
  templateMessages?: ProviderMessageType[];
  promptLibraryVersions?: LogExperimentPromptVersion[];
  promptLibraryMetadata?: PromptLibraryMetadata;
  experimentName?: string;
  configs: LLMPromptConfigsType;
  openAiPipelineMode?: OpenAiPipelineMode;
  selectedRuleIds: string[] | null;
  datasetItemData?: object;
}

export interface TraceMapping {
  traceId: string;
  promptId: string;
  datasetItemId?: string;
}

export interface LogProcessorArgs {
  onError: (error: Error) => void;
  onCreateTraces: (traces: LogTrace[], mappings: TraceMapping[]) => void;
  projectName?: string;
}

export interface LogProcessor {
  log: (run: LogQueueParams) => void;
  finishLogging: () => void;
}

export const NOOP_LOG_PROCESSOR: LogProcessor = {
  log: () => {},
  finishLogging: () => {},
};

export const buildLogProcessor = ({
  canLogTraceSpanThread,
  args,
}: {
  canLogTraceSpanThread: boolean;
  args: LogProcessorArgs;
}): LogProcessor =>
  canLogTraceSpanThread
    ? createLogPlaygroundProcessor(args)
    : NOOP_LOG_PROCESSOR;

const createBatchTraces = async (traces: LogTrace[]) => {
  return api.post(`${TRACES_REST_ENDPOINT}batch`, {
    traces: traces.map(snakeCaseObj),
  });
};

const createBatchSpans = async (spans: LogSpan[]) => {
  return api.post(`${SPANS_REST_ENDPOINT}batch`, {
    spans: spans.map(snakeCaseObj),
  });
};

const PLAYGROUND_TRACE_SPAN_NAME = "chat_completion_create";
const USAGE_FIELDS_TO_SEND = [
  "completion_tokens",
  "prompt_tokens",
  "total_tokens",
];

const getRunErrorInfo = (run: LogQueueParams): LogErrorInfo | undefined => {
  const error = parseCompletionError(run);
  if (!error) return undefined;

  return {
    exception_type: error.exceptionType,
    message: error.message,
  };
};

const getTraceFromRun = (
  run: LogQueueParams,
  projectName: string,
  source: LOGS_SOURCE,
): LogTrace => {
  const errorInfo = getRunErrorInfo(run);

  const trace: LogTrace = {
    id: v7(),
    projectName,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: {
      messages: run.providerMessages,
    },
    output: { output: parseCompletionOutput(run) },
    ...(errorInfo && { errorInfo }),
    metadata: {
      created_from: "playground",
    },
    source,
  };

  // Add selected_rule_ids to trace metadata if provided
  if (run.selectedRuleIds && run.selectedRuleIds.length > 0) {
    trace.metadata = {
      ...trace.metadata,
      selected_rule_ids: run.selectedRuleIds,
    };
  }

  // Add dataset_item_data to trace metadata if provided
  if (run.datasetItemData) {
    trace.metadata = {
      ...trace.metadata,
      dataset_item_data: run.datasetItemData,
    };
  }

  // Add opik_prompts to trace metadata if prompt is from library and unchanged
  // This follows the Python SDK format for associating prompts with traces
  if (run.promptLibraryMetadata) {
    trace.metadata = {
      ...trace.metadata,
      opik_prompts: [run.promptLibraryMetadata],
    };
  }

  return trace;
};

const hasChoicesContent = (run: LogQueueParams): boolean => {
  return !!run?.choices?.some((choice) => choice.delta.content);
};

const getSpanFromRun = (
  run: LogQueueParams,
  traceId: string,
  projectName: string,
  source: LOGS_SOURCE,
): LogSpan => {
  const errorInfo = getRunErrorInfo(run);

  const spanOutput =
    run.choices && hasChoicesContent(run)
      ? { choices: run.choices }
      : { output: run.result };

  // Use the actual model and provider from the response headers if available
  // This is important for the default provider which uses a virtual model name and provider which is transformed at inference time
  const spanModel = run.actualModel || run.model;
  const spanProvider = run.actualProvider || run.provider;

  return {
    id: v7(),
    traceId,
    projectName,
    type: SPAN_TYPE.llm,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: {
      messages: run.providerMessages,
    },
    output: spanOutput,
    ...(errorInfo && { errorInfo }),
    usage: !run.usage ? undefined : pick(run.usage, USAGE_FIELDS_TO_SEND),
    model: spanModel,
    provider: spanProvider,
    source,
    metadata: {
      created_from: spanProvider,
      usage: run.usage,
      model: spanModel,
      parameters: getLoggedParameters(run),
      ...(run.provider === PROVIDER_TYPE.OPIK_FREE && {
        opik_free_model: true,
      }),
    },
  };
};

/**
 * What the request actually carried, for the trace to record.
 *
 * The stored config deliberately keeps a parameter the selected model rejects so that switching
 * back to one that accepts it restores the value, and sanitizeConfigForRequest is what decides
 * which of those reach the provider. Logging the raw config instead would report a temperature the
 * call never ran at.
 */
export const getLoggedParameters = (
  run: Pick<LogQueueParams, "model" | "configs" | "openAiPipelineMode">,
): Record<string, unknown> =>
  sanitizeConfigForRequest(
    run.model,
    run.configs as unknown as Record<string, unknown>,
    run.openAiPipelineMode,
  );

const createLogPlaygroundProcessor = ({
  onError,
  onCreateTraces,
  projectName = PLAYGROUND_PROJECT_NAME,
}: LogProcessorArgs): LogProcessor => {
  const traceMappings: TraceMapping[] = [];

  const spanBatch = createBatchProcessor<LogSpan>(async (spans) => {
    try {
      await createBatchSpans(spans);
    } catch {
      onError(new Error("There has been an error with logging spans"));
    }
  });

  const traceBatch = createBatchProcessor<LogTrace>(async (traces) => {
    try {
      await createBatchTraces(traces);
      onCreateTraces(traces, traceMappings);
    } catch {
      onError(new Error("There has been an error with logging traces"));
    }
  });

  return {
    log: (run: LogQueueParams) => {
      const trace = getTraceFromRun(run, projectName, LOGS_SOURCE.playground);
      const span = getSpanFromRun(
        run,
        trace.id,
        projectName,
        LOGS_SOURCE.playground,
      );

      traceMappings.push({ traceId: trace.id, promptId: run.promptId });

      traceBatch.addItem(trace);
      spanBatch.addItem(span);
    },
    finishLogging: () => {
      traceBatch.flush();
      spanBatch.flush();
    },
  };
};

export default createLogPlaygroundProcessor;
