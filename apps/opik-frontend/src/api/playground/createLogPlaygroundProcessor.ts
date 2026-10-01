import { v7 } from "uuid";
import pick from "lodash/pick";
import omit from "lodash/omit";
import isPlainObject from "lodash/isPlainObject";

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
import { getRequestParameters } from "@/lib/modelUtils";
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
import { parseComposedProviderType } from "@/lib/provider";
import {
  isSentToProvider,
  PENALTY_KEYS,
  TOKEN_LIMIT_KEY_SENT,
  TOKEN_LIMIT_KEYS,
} from "@/lib/extraBodyParameters";

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

// Mirrors the backend (CustomLlmProvider.normalizeForProvider), which runs before the extra body is
// applied: Ollama reads only max_tokens and Bedrock only max_completion_tokens, so each gets the limit
// under its own name.
const normalizeForProvider = (
  provider: PROVIDER_TYPE,
  parameters: Record<string, unknown>,
  extraBody?: Record<string, unknown>,
) => {
  const limitKey = TOKEN_LIMIT_KEY_SENT[provider];
  if (!limitKey) return { parameters, extraBody };

  const sliderLimit = [
    parameters.max_completion_tokens,
    parameters.max_tokens,
  ].find((value) => isSentToProvider(provider, limitKey, value));
  const unsentPenalties = PENALTY_KEYS.filter(
    (key) => !isSentToProvider(provider, key, parameters[key]),
  );
  const otherLimitKey = TOKEN_LIMIT_KEYS.find((key) => key !== limitKey)!;

  return {
    parameters: {
      ...omit(parameters, [...TOKEN_LIMIT_KEYS, ...unsentPenalties]),
      ...(sliderLimit !== undefined && { [limitKey]: sliderLimit }),
    },
    // The backend moves the limit with putIfAbsent, which also replaces a null, so a null under the
    // provider's own name does not hide the value under the other name.
    extraBody:
      extraBody && otherLimitKey in extraBody
        ? {
            ...omit(extraBody, otherLimitKey),
            [limitKey]: extraBody[limitKey] ?? extraBody[otherLimitKey],
          }
        : extraBody,
  };
};

export const getLoggedParameters = (
  run: Pick<LogQueueParams, "model" | "configs" | "openAiPipelineMode"> &
    Partial<Pick<LogQueueParams, "provider">>,
): Record<string, unknown> => {
  const { custom_parameters: customParameters, ...requestParameters } =
    getRequestParameters(run.model, run.configs, run.openAiPipelineMode);
  const { parameters, extraBody } = normalizeForProvider(
    parseComposedProviderType(run.provider ?? ""),
    requestParameters,
    isPlainObject(customParameters)
      ? (customParameters as Record<string, unknown>)
      : undefined,
  );
  if (!extraBody) return parameters;

  // Mirrors the backend (CustomParametersOverrides): it sends each extra body key at the top level
  // in place of the field of the same name, and treats the two token limit names as one limit.
  const replacesTokenLimit = TOKEN_LIMIT_KEYS.some((key) => key in extraBody);
  return {
    ...(replacesTokenLimit ? omit(parameters, TOKEN_LIMIT_KEYS) : parameters),
    ...extraBody,
  };
};

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
