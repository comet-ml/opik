import { useCallback, RefObject } from "react";
import { LogProcessor } from "@/api/playground/createLogPlaygroundProcessor";
import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import {
  usePromptIds,
  usePromptMap,
  useUpdateOutput,
} from "@/store/PlaygroundStore";
import useCompletionProxyStreaming from "@/api/playground/useCompletionProxyStreaming";
import {
  LLMMessage,
  MessageContent,
  TextPart,
  ImagePart,
  VideoPart,
  AudioPart,
  ProviderMessageType,
} from "@/types/llm";
import { getPromptMustacheTags } from "@/lib/prompt";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import mustache from "mustache";
import cloneDeep from "lodash/cloneDeep";
import set from "lodash/set";
import isObject from "lodash/isObject";
import { parseCompletionOutput } from "@/lib/playground";
import { useHydrateDatasetItemData } from "@/components/pages/PlaygroundPage/useHydrateDatasetItemData";
import { getTextFromMessageContent } from "@/lib/llm";

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
  // Extract mustache tags from text content
  const messageTags = getPromptMustacheTags(
    getTextFromMessageContent(message.content),
  );

  // Validate variables exist
  const serializedDatasetItem = serializeTags(datasetItem, messageTags);
  const notDefinedVariables = messageTags.filter((tag) =>
    isUndefined(get(serializedDatasetItem, tag)),
  );

  if (notDefinedVariables.length > 0) {
    throw new Error(`${notDefinedVariables.join(", ")} not defined`);
  }

  // Handle content based on type
  let processedContent: MessageContent;

  if (typeof message.content === "string") {
    // Text-only: render mustache and keep as string
    processedContent = mustache.render(
      message.content,
      serializedDatasetItem,
      {},
      { escape: (val: string) => val },
    );
  } else {
    // Array with images/videos/audios: render mustache in text and media URLs
    processedContent = message.content.map((part) => {
      if (part.type === "text") {
        return {
          type: "text",
          text: mustache.render(
            part.text,
            serializedDatasetItem,
            {},
            { escape: (val: string) => val },
          ),
        } as TextPart;
      } else if (part.type === "image_url") {
        // Render mustache variables in image URLs
        return {
          type: "image_url",
          image_url: {
            url: mustache.render(
              part.image_url.url,
              serializedDatasetItem,
              {},
              { escape: (val: string) => val },
            ),
          },
        } as ImagePart;
      } else if (part.type === "video_url") {
        // Render mustache variables in video URLs
        return {
          type: "video_url",
          video_url: {
            url: mustache.render(
              part.video_url.url,
              serializedDatasetItem,
              {},
              { escape: (val: string) => val },
            ),
          },
        } as VideoPart;
      } else {
        // Render mustache variables in audio URLs
        return {
          type: "audio_url",
          audio_url: {
            url: mustache.render(
              part.audio_url.url,
              serializedDatasetItem,
              {},
              { escape: (val: string) => val },
            ),
          },
        } as AudioPart;
      }
    });
  }

  return {
    role: message.role,
    // Send content as-is (either string or array) to match OpenAI API spec
    content: processedContent,
  };
};

interface UsePromptDatasetItemCombinationArgs {
  datasetItems: DatasetItem[];
  isToStopRef: RefObject<boolean>;
  workspaceName: string;
  datasetName: string | null;
  datasetVersionId?: string;
  selectedRuleIds: string[] | null;
  addAbortController: (key: string, value: AbortController) => void;
  deleteAbortController: (key: string) => void;
  throttlingSeconds: number;
}

const usePromptDatasetItemCombination = ({
  datasetItems,
  isToStopRef,
  workspaceName,
  datasetName,
  datasetVersionId,
  selectedRuleIds,
  addAbortController,
  deleteAbortController,
  throttlingSeconds,
}: UsePromptDatasetItemCombinationArgs) => {
  const updateOutput = useUpdateOutput();
  const hydrateDatasetItemData = useHydrateDatasetItemData();

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
      if (isToStopRef.current) {
        return;
      }

      const controller = new AbortController();

      const datasetItemId = datasetItem?.id || "";
      const datasetItemData = await hydrateDatasetItemData(datasetItem);
      const key = `${datasetItemId}-${prompt.id}`;

      addAbortController(key, controller);

      try {
        updateOutput(prompt.id, datasetItemId, {
          isLoading: true,
          selectedRuleIds,
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
          datasetVersionId,
          datasetItemId: datasetItemId,
          selectedRuleIds,
          datasetItemData,
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

        // Apply throttling delay if configured
        if (throttlingSeconds > 0 && !isToStopRef.current) {
          await new Promise((resolve) =>
            setTimeout(resolve, throttlingSeconds * 1000),
          );
        }
      }
    },

    [
      isToStopRef,
      hydrateDatasetItemData,
      addAbortController,
      updateOutput,
      runStreaming,
      datasetName,
      datasetVersionId,
      deleteAbortController,
      selectedRuleIds,
      throttlingSeconds,
    ],
  );

  return {
    createCombinations,
    processCombination,
  };
};

export default usePromptDatasetItemCombination;
