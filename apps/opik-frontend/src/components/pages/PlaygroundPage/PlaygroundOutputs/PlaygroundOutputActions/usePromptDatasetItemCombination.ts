import { useCallback, useEffect, useRef } from "react";
import { LogProcessor } from "@/api/playground/createLogPlaygroundProcessor";
import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import {
  usePromptIds,
  usePromptMap,
  useUpdateOutput,
} from "@/store/PlaygroundStore";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import { LLMMessage, ProviderMessageType } from "@/types/llm";
import { getMessageContentMustacheTags, renderMessageContent } from "@/lib/llm";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import cloneDeep from "lodash/cloneDeep";
import set from "lodash/set";
import isObject from "lodash/isObject";
import { parseCompletionOutput } from "@/lib/playground";
import { processInputData } from "@/lib/images";

export interface DatasetItemPromptCombination {
  datasetItem?: DatasetItem;
  prompt: PlaygroundPromptType;
}

const serializeTags = (datasetItem: DatasetItem["data"], tags: string[]) => {
  const newDatasetItem = cloneDeep(datasetItem) as Record<string, unknown>;

  const placeholderMap = (() => {
    try {
      const { images } = processInputData(datasetItem as object);
      const map = images.reduce<Record<string, string>>((acc, image) => {
        if (!image.name || !image.url) {
          return acc;
        }

        const match = image.name.match(/\[(image(?:_\d+)?)\]/i);
        if (match) {
          acc[`[${match[1]}]`] = image.url;
        }

        return acc;
      }, {});

      if (!map["[image]"] && images[0]?.url) {
        map["[image]"] = images[0].url;
      }

      return map;
    } catch (error) {
      if (import.meta.env.DEV) {
        // eslint-disable-next-line no-console
        console.debug(
          "[Playground] Failed to build image placeholder map",
          error,
        );
      }
      return {};
    }
  })();

  tags.forEach((tag) => {
    const value = get(newDatasetItem, tag);
    if (import.meta.env.DEV) {
      // eslint-disable-next-line no-console
      console.debug("[Playground] dataset value", tag, value);
    }
    if (typeof value === "string") {
      const normalized = value.trim().replace(/^"(.*)"$/, "$1");
      if (placeholderMap[normalized]) {
        set(newDatasetItem, tag, placeholderMap[normalized]);
        return;
      }

      if (
        !normalized.startsWith("data:image") &&
        placeholderMap["[image]"] &&
        tag.toLowerCase().includes("image")
      ) {
        set(newDatasetItem, tag, placeholderMap["[image]"]);
        return;
      }

      set(newDatasetItem, tag, normalized);
      return;
    }

    set(newDatasetItem, tag, isObject(value) ? JSON.stringify(value) : value);
  });

  if (import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.debug("[Playground] Serialized dataset item", newDatasetItem);
  }

  return newDatasetItem;
};

const transformMessageIntoProviderMessage = (
  message: LLMMessage,
  datasetItem: DatasetItem["data"] = {},
): ProviderMessageType => {
  const messageTags = getMessageContentMustacheTags(message.content);
  const serializedDatasetItem = serializeTags(datasetItem, messageTags);
  if (import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.debug(
      "[Playground] Message tags",
      messageTags,
      serializedDatasetItem,
    );
  }

  const notDefinedVariables = messageTags.filter((tag) =>
    isUndefined(get(serializedDatasetItem, tag)),
  );

  if (notDefinedVariables.length > 0) {
    throw new Error(`${notDefinedVariables.join(", ")} not defined`);
  }

  return {
    role: message.role,
    content: renderMessageContent(message.content, serializedDatasetItem),
  };
};

interface UsePromptDatasetItemCombinationArgs {
  datasetItems: DatasetItem[];
  isToStop: boolean;
  workspaceName: string;
  datasetName: string | null;
  addAbortController: (key: string, value: AbortController) => void;
  deleteAbortController: (key: string) => void;
}

const usePromptDatasetItemCombination = ({
  datasetItems,
  isToStop,
  workspaceName,
  datasetName,
  addAbortController,
  deleteAbortController,
}: UsePromptDatasetItemCombinationArgs) => {
  const updateOutput = useUpdateOutput();

  // the reason why we need ref here is that the value is taken in a deep callback
  // the prop is just taken as the value on the moment of creation
  const isToStopRef = useRef(isToStop);

  const runStreaming = useCompletionProxyStreaming({
    workspaceName,
  });

  useEffect(() => {
    isToStopRef.current = isToStop;
  }, [isToStop]);

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
      if (isToStopRef.current) {
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

        const promptLibraryVersions = (
          prompt.messages
            .map((m) => m.promptVersionId)
            .filter(Boolean) as string[]
        ).map((id) => ({
          id,
        }));

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

        updateOutput(prompt.id, datasetItemId, {
          isLoading: false,
        });

        logProcessor.log({
          ...run,
          providerMessages,
          promptLibraryVersions,
          configs: prompt.configs,
          model: prompt.model,
          provider: prompt.provider,
          promptId: prompt.id,
          datasetName,
          datasetItemId: datasetItemId,
        });

        if (
          run.opikError ||
          run.providerError ||
          run.pythonProxyError ||
          !run.result
        ) {
          throw new Error(parseCompletionOutput(run));
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

    [
      addAbortController,
      updateOutput,
      runStreaming,
      datasetName,
      deleteAbortController,
    ],
  );

  return {
    createCombinations,
    processCombination,
  };
};

export default usePromptDatasetItemCombination;
