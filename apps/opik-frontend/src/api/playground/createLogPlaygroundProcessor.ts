import asyncLib from "async";
import { v7 } from "uuid";
import pick from "lodash/pick";

import {
  LogExperiment,
  LogExperimentItem,
  LogExperimentPromptVersion,
  LogSpan,
  LogTrace,
} from "@/types/playground";

import { SPAN_TYPE } from "@/types/traces";
import api, {
  EXPERIMENTS_REST_ENDPOINT,
  SPANS_REST_ENDPOINT,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { snakeCaseObj } from "@/lib/utils";
import { createBatchProcessor } from "@/lib/batches";
import { RunStreamingReturn } from "@/api/playground/useCompletionProxyStreaming";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { ProviderMessageType } from "@/types/llm";
import { parseCompletionOutput } from "@/lib/playground";
import { PLAYGROUND_PROJECT_NAME } from "@/constants/shared";

export interface LogQueueParams extends RunStreamingReturn {
  promptId: string;
  datasetItemId?: string;
  datasetName: string | null;
  model: PROVIDER_MODEL_TYPE | "";
  provider: COMPOSED_PROVIDER_TYPE | "";
  providerMessages: ProviderMessageType[];
  promptLibraryVersions?: LogExperimentPromptVersion[];
  configs: LLMPromptConfigsType;
  selectedRuleIds: string[] | null;
  datasetItemData?: object;
}

export interface TraceMapping {
  traceId: string;
  promptId: string;
  datasetItemId?: string;
}

export interface LogProcessorArgs {
  onAddExperimentRegistry: (loggedExperiments: LogExperiment[]) => void;
  onError: (error: Error) => void;
  onCreateTraces: (traces: LogTrace[], mappings: TraceMapping[]) => void;
}

export interface LogProcessor {
  log: (run: LogQueueParams) => void;
}

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

const PLAYGROUND_TRACE_SPAN_NAME = "chat_completion_create";
const USAGE_FIELDS_TO_SEND = [
  "completion_tokens",
  "prompt_tokens",
  "total_tokens",
];

const getTraceFromRun = (run: LogQueueParams): LogTrace => {
  const trace: LogTrace = {
    id: v7(),
    projectName: PLAYGROUND_PROJECT_NAME,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: {
      messages: run.providerMessages,
    },
    output: { output: parseCompletionOutput(run) },
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

  return trace;
};

const hasChoicesContent = (run: LogQueueParams): boolean => {
  return !!run?.choices?.some((choice) => choice.delta.content);
};

const getSpanFromRun = (run: LogQueueParams, traceId: string): LogSpan => {
  const spanOutput =
    run.choices && hasChoicesContent(run)
      ? { choices: run.choices }
      : { output: run.result };

  return {
    id: v7(),
    traceId,
    projectName: PLAYGROUND_PROJECT_NAME,
    type: SPAN_TYPE.llm,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: {
      messages: run.providerMessages,
    },
    output: spanOutput,
    usage: !run.usage ? undefined : pick(run.usage, USAGE_FIELDS_TO_SEND),
    model: run.model,
    provider: run.provider,
    metadata: {
      created_from: run.provider,
      usage: run.usage,
      model: run.model,
      parameters: run.configs,
    },
  };
};

const getExperimentFromRun = (run: LogQueueParams): LogExperiment => {
  const experimentMetadata: Record<string, unknown> = {
    model: run.model,
    messages: JSON.stringify(run.providerMessages),
    model_config: run.configs,
  };

  // Add selected_rule_ids to experiment metadata if provided
  if (run.selectedRuleIds && run.selectedRuleIds.length > 0) {
    experimentMetadata.selected_rule_ids = run.selectedRuleIds;
  }

  return {
    id: v7(),
    datasetName: run.datasetName!,
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
}: LogProcessorArgs): LogProcessor => {
  const experimentPromptMap: Record<string, string> = {};
  const experimentRegistry: LogExperiment[] = [];
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
    onAddExperimentRegistry(experimentRegistry);
  });

  return {
    log: (run: LogQueueParams) => {
      const { promptId, datasetName, datasetItemId } = run;

      const isWithExperiments = !!datasetName;

      const trace = getTraceFromRun(run);
      const span = getSpanFromRun(run, trace.id);

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
  };
};

export default createLogPlaygroundProcessor;
