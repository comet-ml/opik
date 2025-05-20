import asyncLib from "async";
import { v7 } from "uuid";
import pick from "lodash/pick";

import {
  LogExperiment,
  LogExperimentItem,
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
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { ProviderMessageType } from "@/types/llm";

export interface LogQueueParams extends RunStreamingReturn {
  promptId: string;
  datasetItemId?: string;
  datasetName: string | null;
  model: PROVIDER_MODEL_TYPE | "";
  provider: PROVIDER_TYPE | "";
  providerMessages: ProviderMessageType[];
  configs: LLMPromptConfigsType;
}

export interface LogProcessorArgs {
  onAddExperimentRegistry: (loggedExperiments: LogExperiment[]) => void;
  onError: (error: Error) => void;
  onCreateTraces: (traces: LogTrace[]) => void;
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

const PLAYGROUND_PROJECT_NAME = "playground";
const PLAYGROUND_TRACE_SPAN_NAME = "chat_completion_create";
const USAGE_FIELDS_TO_SEND = [
  "completion_tokens",
  "prompt_tokens",
  "total_tokens",
];

const getTraceFromRun = (run: LogQueueParams): LogTrace => {
  return {
    id: v7(),
    projectName: PLAYGROUND_PROJECT_NAME,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: { messages: run.providerMessages },
    output: { output: run.result || run.providerError },
  };
};

const getSpanFromRun = (run: LogQueueParams, traceId: string): LogSpan => {
  return {
    id: v7(),
    traceId,
    projectName: PLAYGROUND_PROJECT_NAME,
    type: SPAN_TYPE.llm,
    name: PLAYGROUND_TRACE_SPAN_NAME,
    startTime: run.startTime,
    endTime: run.endTime,
    input: { messages: run.providerMessages },
    output: { choices: run.choices ? run.choices : [] },
    usage: !run.usage ? undefined : pick(run.usage, USAGE_FIELDS_TO_SEND),
    metadata: {
      created_from: run.provider,
      usage: run.usage,
      model: run.model,
      parameters: run.configs,
    },
  };
};

const getExperimentFromRun = (run: LogQueueParams): LogExperiment => {
  return {
    id: v7(),
    datasetName: run.datasetName!,
    metadata: {
      model: run.model,
      messages: JSON.stringify(run.providerMessages),
      model_config: run.configs,
    },
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
      onCreateTraces(traces);
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
      const { promptId, datasetName } = run;

      const isWithExperiments = !!datasetName;

      const trace = getTraceFromRun(run);
      const span = getSpanFromRun(run, trace.id);

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
