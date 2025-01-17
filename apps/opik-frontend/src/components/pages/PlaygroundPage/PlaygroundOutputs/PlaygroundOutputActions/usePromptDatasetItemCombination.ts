import { useCallback } from "react";
import { LogProcessor } from "@/api/playground/createLogPlaygroundProcessor";
import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import {
  PlaygroundStore,
  usePromptIds,
  usePromptMap,
  useUpdateOutput,
} from "@/store/PlaygroundStore";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import { LLMMessage, ProviderMessageType } from "@/types/llm";
import { getPromptMustacheTags } from "@/lib/prompt";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import mustache from "mustache";
import cloneDeep from "lodash/cloneDeep";
import set from "lodash/set";
import isObject from "lodash/isObject";

export interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

const serializeTags = (datasetItem: DatasetItem["data"], tags: string[]) => {
  const newDatasetItem = cloneDeep(datasetItem);

  tags.forEach((tag) => {
    const value = get(newDatasetItem, tag);
    set(newDatasetItem, tag, isObject(value) ? JSON.stringify(value) : value);
  });

  return newDatasetItem;
};

const transformMessageIntoProviderMessage = (
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

interface UsePromptDatasetItemCombinationArgs {
  datasetItems: DatasetItem[];
  isStopped: boolean;
  workspaceName: string;
  datasetName: string | null;
  addAbortController: (key: string, value: AbortController) => void;
  deleteAbortController: (key: string) => void;
}

const usePromptDatasetItemCombination = ({
  datasetItems,
  isStopped,
  workspaceName,
  datasetName,
  addAbortController,
  deleteAbortController,
}: UsePromptDatasetItemCombinationArgs) => {
  const updateOutput = useUpdateOutput();

  const runStreaming = useCompletionProxyStreaming({
    workspaceName,
  });

  const promptIds = usePromptIds();
  const promptMap = usePromptMap();

  const createCombinations = useCallback((): DatasetItemPromptCombination[] => {
    if (datasetItems.length > 0 && promptIds.length > 0) {
      return datasetItems.flatMap((di) =>
        promptIds.map((promptId) => ({
          datasetItem: di,
          prompt: promptMap[promptId],
        })),
      );
    }

    return promptIds.map((promptId) => ({
      prompt: promptMap[promptId],
    }));
  }, [datasetItems, promptMap, promptIds]);

  const processCombination = useCallback(
    async (
      { datasetItem, prompt }: DatasetItemPromptCombination,
      logProcessor: LogProcessor,
    ) => {
      if (isStopped) {
        return;
      }

      const controller = new AbortController();

      const datasetItemId = datasetItem?.id || "";
      const datasetItemData = datasetItem?.data || {};
      const key = `${datasetItemId}-${prompt.id}`;

      addAbortController(key, controller);

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

        logProcessor.log({
          ...run,
          providerMessages,
          configs: prompt.configs,
          model: prompt.model,

          promptId: prompt.id,
          datasetName,
          datasetItemId: datasetItemId,
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
      } finally {
        deleteAbortController(key);
      }
    },

    [datasetName, runStreaming, updateOutput],
  );

  return {
    createCombinations,
    processCombination,
  };
};

export default usePromptDatasetItemCombination;
