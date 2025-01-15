import { useCallback, useRef, useState } from "react";
import asyncLib from "async";
import mustache from "mustache";
import isUndefined from "lodash/isUndefined";

import get from "lodash/get";
import set from "lodash/set";
import isObject from "lodash/isObject";
import cloneDeep from "lodash/cloneDeep";

import { DatasetItem } from "@/types/datasets";
import { LLMMessage, ProviderMessageType } from "@/types/llm";
import { PlaygroundPromptType } from "@/types/playground";
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

const serializeTags = (datasetItem: DatasetItem["data"], tags: string[]) => {
  const newDatasetItem = cloneDeep(datasetItem);

  tags.forEach((tag) => {
    const value = get(newDatasetItem, tag);
    set(newDatasetItem, tag, isObject(value) ? JSON.stringify(value) : value);
  });

  return newDatasetItem;
};

export const transformMessageIntoProviderMessage = (
  message: LLMMessage,
  datasetItem: DatasetItem["data"] = {},
): ProviderMessageType => {
  const messageTags = getPromptMustacheTags(message.content);
  const serializedDatasetItem = serializeTags(datasetItem, messageTags);

  const notDefinedVariables = messageTags.filter((tag) =>
    isUndefined(get(serializedDatasetItem, tag)),
  );

  if (notDefinedVariables.length > 0) {
    throw new Error(`${notDefinedVariables.join(", ")} not defined`);
  }

  return {
    role: message.role,
    content: mustache.render(
      message.content,
      serializedDatasetItem,
      {},
      {
        // avoid escaping of a mustache
        escape: (val: string) => val,
      },
    ),
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
      createTraceSpan,
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

    const processCombination = async ({
      datasetItem,
      prompt,
    }: DatasetItemPromptCombination) => {
      if (isToStopRef.current) {
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
      } catch (error) {
        const typedError = error as Error;

        updateOutput(prompt.id, datasetItemId, {
          value: typedError.message,
          isLoading: false,
        });
      }
    };

    asyncLib.mapLimit(
      combinations,
      LIMIT_STREAMING_CALLS,
      processCombination,
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
