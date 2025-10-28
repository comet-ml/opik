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
import { getPromptMustacheTags } from "@/lib/prompt";
import { IMAGE_TAG_START, IMAGE_TAG_END } from "@/lib/llm";
import { DATA_IMAGE_REGEX, IMAGE_URL_REGEX } from "@/lib/images";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import mustache from "mustache";
import cloneDeep from "lodash/cloneDeep";
import set from "lodash/set";
import isObject from "lodash/isObject";
import { parseCompletionOutput } from "@/lib/playground";
import { useHydrateDatasetItemData } from "@/components/pages/PlaygroundPage/useHydrateDatasetItemData";

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

  // Wrap any raw image URLs or base64 data in the content with <<<image>>>...<<</image>>> tags
  // This is needed when using datasets with images where mustache directly inserts image data
  const renderedContent = wrapImageUrlsWithTags(
    mustache.render(
      message.content,
      serializedDatasetItem,
      {},
      {
        // avoid escaping of a mustache
        escape: (val: string) => val,
      },
    ),
  );

  return {
    role: message.role,
    content: renderedContent,
  };
};

/**
 * Wraps raw image URLs and base64 data URLs in the content with <<<image>>>...<<</image>>> tags.
 * Detects both:
 * - data:image/...;base64,... (base64 encoded images)
 * - http(s)://... image URLs
 * - [image_N] placeholders (from processInputData)
 */
const wrapImageUrlsWithTags = (content: string): string => {
  /**
   * Replacer function that wraps matches with image tags if not already wrapped.
   * Checks the surrounding context at the match position to avoid double-wrapping.
   */
  const wrapIfNotAlreadyWrapped = (
    match: string,
    offset: number,
    string: string,
  ): string => {
    const prefix = string.slice(
      Math.max(0, offset - IMAGE_TAG_START.length),
      offset,
    );
    const suffix = string.slice(
      offset + match.length,
      offset + match.length + IMAGE_TAG_END.length,
    );

    // Already wrapped at this position
    if (prefix === IMAGE_TAG_START && suffix === IMAGE_TAG_END) {
      return match;
    }

    return `${IMAGE_TAG_START}${match}${IMAGE_TAG_END}`;
  };

  let processedContent = content;

  // Wrap data URLs
  processedContent = processedContent.replace(
    DATA_IMAGE_REGEX,
    wrapIfNotAlreadyWrapped,
  );

  // Wrap HTTP(S) image URLs
  processedContent = processedContent.replace(
    IMAGE_URL_REGEX,
    wrapIfNotAlreadyWrapped,
  );

  return processedContent;
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
  const hydrateDatasetItemData = useHydrateDatasetItemData();

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
      const datasetItemData = await hydrateDatasetItemData(datasetItem);
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
      hydrateDatasetItemData,
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
