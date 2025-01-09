import { useCallback, useRef, useState } from "react";
import asyncLib from "async";
import mustache from "mustache";
import isUndefined from "lodash/isUndefined";
import isObject from "lodash/isObject";

import { DatasetItem } from "@/types/datasets";
import {
  PlaygroundMessageType,
  PlaygroundPromptType,
  ProviderMessageType,
} from "@/types/playground";
import {
  usePromptIds,
  usePromptMap,
  useResetOutputMap,
  useUpdateOutput,
} from "@/store/PlaygroundStore";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import useCreateOutputTraceAndSpan, {
  CreateTraceSpanParams,
} from "@/api/playground/useCreateOutputTraceAndSpan";
import { getPromptMustacheTags } from "@/lib/prompt";

const LIMIT_STREAMING_CALLS = 5;
const LIMIT_LOG_CALLS = 2;

interface FlattenStackItem {
  obj: object;
  prefix: string;
}

function flattenObject(obj: object): Record<string, unknown> {
  const stack: FlattenStackItem[] = [{ obj, prefix: "" }];
  const result: Record<string, unknown> = {};

  while (stack.length) {
    const { obj: currentObj, prefix: currentPrefix } = stack.pop()!;

    for (const [key, value] of Object.entries(currentObj)) {
      const fullKey = currentPrefix ? `${currentPrefix}.${key}` : key;

      if (Array.isArray(value)) {
        value.forEach((item, index) => {
          if (typeof item === "string") {
            result[`${fullKey}.${index}`] = item;
            return;
          }

          if (item !== null && isObject(item)) {
            // nested
            stack.push({ obj: item, prefix: `${fullKey}[${index}]` });
            return;
          }

          // primitive
          result[`${fullKey}.${index}`] = item;
        });
        continue;
      }

      if (value !== null && isObject(value)) {
        stack.push({ obj: value, prefix: fullKey });
        continue;
      }

      result[fullKey] = value;
    }
  }

  return result;
}

export const transformMessageIntoProviderMessage = (
  message: PlaygroundMessageType,
  datasetItem: DatasetItem["data"] = {},
): ProviderMessageType => {
  const messageTags = getPromptMustacheTags(message.content);
  const flattenedDatasetItem = flattenObject(datasetItem);

  const notDefinedVariables = messageTags.filter((tag) =>
    isUndefined(flattenedDatasetItem[tag]),
  );

  if (notDefinedVariables.length > 0) {
    throw new Error(`${notDefinedVariables.join(", ")} not defined`);
  }

  return {
    role: message.role,
    content: mustache.render(message.content, datasetItem),
  };
};

interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

interface UseActionButtonActionsArguments {
  datasetItems: DatasetItem[];
  workspaceName: string;
}

const useActionButtonActions = ({
  datasetItems,
  workspaceName,
}: UseActionButtonActionsArguments) => {
  const [isRunning, setIsRunning] = useState(false);

  const isToStopRef = useRef(false);
  const abortControllersOngoingRef = useRef(new Map<string, AbortController>());

  const promptMap = usePromptMap();
  const promptIds = usePromptIds();
  const updateOutput = useUpdateOutput();
  const resetOutputMap = useResetOutputMap();

  const createTraceSpan = useCreateOutputTraceAndSpan();
  const runStreaming = useCompletionProxyStreaming({
    workspaceName,
  });

  const stopAll = useCallback(() => {
    // nothing to stop
    if (abortControllersOngoingRef.current.size === 0) {
      return;
    }

    isToStopRef.current = true;
    abortControllersOngoingRef.current.forEach((controller) =>
      controller.abort(),
    );

    abortControllersOngoingRef.current.clear();
  }, []);

  const runAll = useCallback(async () => {
    resetOutputMap();
    setIsRunning(true);

    const asyncLogQueue = asyncLib.queue<CreateTraceSpanParams>(
      async (task) => {
        await createTraceSpan(task);
      },
      LIMIT_LOG_CALLS,
    );

    let combinations: DatasetItemPromptCombination[] = [];

    if (datasetItems.length > 0) {
      combinations = datasetItems.flatMap((di) =>
        promptIds.map((promptId) => ({
          datasetItem: di,
          prompt: promptMap[promptId],
        })),
      );
    } else if (promptIds.length > 0) {
      combinations = promptIds.map((promptId) => ({
        prompt: promptMap[promptId],
      }));
    }

    const processCombination = async (
      { datasetItem, prompt }: DatasetItemPromptCombination,
      processCallback: asyncLib.AsyncResultCallback<DatasetItemPromptCombination>,
    ) => {
      if (isToStopRef.current) {
        processCallback(null);
        return;
      }

      const controller = new AbortController();

      const datasetItemId = datasetItem?.id || "";
      const datasetItemData = datasetItem?.data || {};

      const key = datasetItemId ? `${datasetItemId}-${prompt.id}` : prompt.id;
      abortControllersOngoingRef.current.set(key, controller);

      try {
        updateOutput(prompt.id, datasetItemId, {
          isLoading: true,
        });

        const providerMessages = prompt.messages.map((m) =>
          transformMessageIntoProviderMessage(m, datasetItemData),
        );

        const run = await runStreaming({
          model: prompt.model,
          messages: providerMessages,
          configs: prompt.configs,
          signal: controller.signal,
          onAddChunk: (o) => {
            updateOutput(prompt.id, datasetItemId, {
              value: o,
            });
          },
        });

        const error = run.opikError || run.providerError;

        updateOutput(prompt.id, datasetItemId, {
          isLoading: false,
        });

        asyncLogQueue.push({
          ...run,
          providerMessages,
          configs: prompt.configs,
          model: prompt.model,
        });

        if (error) {
          throw new Error(error);
        }

        processCallback(null);
      } catch (error) {
        const typedError = error as Error;

        updateOutput(prompt.id, datasetItemId, {
          value: typedError.message,
          isLoading: false,
        });
        processCallback(null);
      }
    };

    asyncLib.mapLimit(
      combinations,
      LIMIT_STREAMING_CALLS,
      (combination: DatasetItemPromptCombination, callback) => {
        processCombination(combination, callback);
      },
      () => {
        setIsRunning(false);
        isToStopRef.current = false;
        abortControllersOngoingRef.current.clear();
      },
    );
  }, [
    resetOutputMap,
    promptIds,
    datasetItems,
    promptMap,
    createTraceSpan,
    runStreaming,
    updateOutput,
  ]);

  return {
    isRunning,
    runAll,
    stopAll,
  };
};

export default useActionButtonActions;
