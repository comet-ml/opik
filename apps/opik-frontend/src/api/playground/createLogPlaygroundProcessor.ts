import asyncLib from "async";
import { v7 } from "uuid";
import pick from "lodash/pick";
import omit from "lodash/omit";
import isPlainObject from "lodash/isPlainObject";
import isNumber from "lodash/isNumber";

import {
  LogExperiment,
  LogExperimentItem,
  LogExperimentPromptVersion,
  LogErrorInfo,
  LogSpan,
  LogTrace,
  PromptLibraryMetadata,
} from "@/types/playground";

import { LOGS_SOURCE, SPAN_TYPE } from "@/types/traces";
import api, {
  EXPERIMENTS_REST_ENDPOINT,
  SPANS_REST_ENDPOINT,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { createBatchProcessor } from "@/lib/batches";
import {
  getRequestParameters,
  RunStreamingReturn,
} from "@/api/playground/useCompletionProxyStreaming";
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
  onAddExperimentRegistry: (
    loggedExperiments: LogExperiment[],
    experimentPromptMap: Record<string, string>,
  ) => void;
  onError: (error: Error) => void;
  onCreateTraces: (traces: LogTrace[], mappings: TraceMapping[]) => void;
  onExperimentItemsComplete?: (experimentIds: string[]) => void;
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
  datasetName,
  canLogTraceSpanThread,
  canCreateExperiments,
  args,
}: {
  datasetName: string | null;
  canLogTraceSpanThread: boolean;
  canCreateExperiments: boolean;
  args: LogProcessorArgs;
}): LogProcessor => {
  const shouldLog = datasetName
    ? canLogTraceSpanThread && canCreateExperiments
    : canLogTraceSpanThread;
  return shouldLog ? createLogPlaygroundProcessor(args) : NOOP_LOG_PROCESSOR;
};

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

const createExperiment = async (experiment: LogExperiment) => {
  return api.post(EXPERIMENTS_REST_ENDPOINT, snakeCaseObj(experiment));
};

const createBatchExperimentItems = async (
  experimentItems: LogExperimentItem[],
) => {
  await api.post(`${EXPERIMENTS_REST_ENDPOINT}items`, {
    experiment_items: experimentItems.map(snakeCaseObj),
  });
};

const finishExperiments = async (experimentIds: string[]) => {
  if (experimentIds.length === 0) {
    return;
  }

  await api.post(`${EXPERIMENTS_REST_ENDPOINT}finish`, {
    ids: experimentIds,
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

const PENALTY_KEYS = ["frequency_penalty", "presence_penalty"];

// Mirrors the backend (CustomLlmProvider.normalizeForProvider), which runs before the extra body is
// applied: Ollama reads only max_tokens and Bedrock only max_completion_tokens, so each gets the limit
// under its own name, and only a positive slider limit. Bedrock also gets no penalty of 0.
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
  ].find((value) => isNumber(value) && value > 0);
  const zeroPenalties =
    provider === PROVIDER_TYPE.BEDROCK
      ? PENALTY_KEYS.filter((key) => parameters[key] === 0)
      : [];
  const otherLimitKey = TOKEN_LIMIT_KEYS.find((key) => key !== limitKey)!;

  return {
    parameters: {
      ...omit(parameters, [...TOKEN_LIMIT_KEYS, ...zeroPenalties]),
      ...(sliderLimit !== undefined && { [limitKey]: sliderLimit }),
    },
    extraBody:
      extraBody && otherLimitKey in extraBody
        ? {
            [limitKey]: extraBody[otherLimitKey],
            ...omit(extraBody, otherLimitKey),
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

const getExperimentFromRun = (run: LogQueueParams): LogExperiment => {
  // Use the actual model from the response headers if available
  const experimentModel = run.actualModel || run.model;

  const experimentMetadata: Record<string, unknown> = {
    model: experimentModel,
    messages: JSON.stringify(run.templateMessages ?? run.providerMessages),
    model_config: getLoggedParameters(run),
  };

  // Add selected_rule_ids to experiment metadata if provided
  if (run.selectedRuleIds && run.selectedRuleIds.length > 0) {
    experimentMetadata.selected_rule_ids = run.selectedRuleIds;
  }

  return {
    id: v7(),
    datasetName: run.datasetName!,
    ...(run.datasetVersionId && {
      datasetVersionId: run.datasetVersionId,
    }),
    ...(run.experimentName && { name: run.experimentName }),
    metadata: experimentMetadata,
    ...(run.promptLibraryVersions?.length && {
      prompt_versions: run.promptLibraryVersions,
    }),
  };
};

const getExperimentItemFromRun = (
  run: LogQueueParams,
  experimentId: string,
  traceId: string,
): LogExperimentItem => {
  return {
    id: v7(),
    datasetItemId: run.datasetItemId!,
    experimentId,
    traceId,
  };
};

const CREATE_EXPERIMENT_CONCURRENCY_RATE = 5;

const createLogPlaygroundProcessor = ({
  onAddExperimentRegistry,
  onError,
  onCreateTraces,
  onExperimentItemsComplete,
  projectName = PLAYGROUND_PROJECT_NAME,
}: LogProcessorArgs): LogProcessor => {
  const experimentPromptMap: Record<string, string> = {};
  const experimentRegistry: LogExperiment[] = [];
  const traceMappings: TraceMapping[] = [];
  let areExperimentsCreated = false;
  let isLoggingFinished = false;

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

  const experimentItemsBatch = createBatchProcessor<LogExperimentItem>(
    async (experimentItems) => {
      try {
        await createBatchExperimentItems(experimentItems);
      } catch {
        onError(
          new Error("There has been an error with logging experiment items"),
        );
      }
    },
  );

  const experimentsQueue = asyncLib.queue<LogExperiment>(async (e) => {
    try {
      await createExperiment(e);
      experimentRegistry.push(e);
    } catch {
      onError(new Error("There has been an error with logging experiments"));
    }
  }, CREATE_EXPERIMENT_CONCURRENCY_RATE);

  experimentsQueue.drain(() => {
    onAddExperimentRegistry(experimentRegistry, experimentPromptMap);
    areExperimentsCreated = true;
    tryFinishExperiments();
  });

  const tryFinishExperiments = async () => {
    // Only finish when both conditions are met:
    // 1. All experiments have been created (queue drained)
    // 2. finishLogging was called (all batches flushed and no more items will be added)
    if (
      areExperimentsCreated &&
      isLoggingFinished &&
      experimentRegistry.length > 0
    ) {
      try {
        const experimentIds = experimentRegistry.map((e) => e.id);
        await finishExperiments(experimentIds);
        onExperimentItemsComplete?.(experimentIds);
      } catch {
        onError(
          new Error("There has been an error with finishing experiments"),
        );
      }
    }
  };

  return {
    log: (run: LogQueueParams) => {
      const { promptId, datasetName, datasetItemId } = run;

      const isWithExperiments = !!datasetName;
      const source = isWithExperiments
        ? LOGS_SOURCE.experiment
        : LOGS_SOURCE.playground;

      const trace = getTraceFromRun(run, projectName, source);
      const span = getSpanFromRun(run, trace.id, projectName, source);

      // Store the trace mapping
      traceMappings.push({
        traceId: trace.id,
        promptId,
        datasetItemId,
      });

      traceBatch.addItem(trace);
      spanBatch.addItem(span);

      if (!isWithExperiments) {
        return;
      }

      // create a missing experiment
      if (!experimentPromptMap[promptId]) {
        const experiment = getExperimentFromRun(run);
        experimentPromptMap[promptId] = experiment.id;
        experimentsQueue.push(experiment);
      }

      const experimentId = experimentPromptMap[promptId];
      const experimentItem = getExperimentItemFromRun(
        run,
        experimentId,
        trace.id,
      );

      experimentItemsBatch.addItem(experimentItem);
    },
    finishLogging: () => {
      // Flush all batches (triggers async API calls)
      spanBatch.flush();
      traceBatch.flush();
      experimentItemsBatch.flush();

      // Mark that logging is finished - no more items will be added
      isLoggingFinished = true;

      // Try to finish experiments if queue has also drained
      tryFinishExperiments();
    },
  };
};

export default createLogPlaygroundProcessor;
