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
import { LLMMessage, ProviderMessageType } from "@/types/llm";
import { getPromptMustacheTags } from "@/lib/prompt";
import {
  IMAGE_TAG_START,
  IMAGE_TAG_END,
  VIDEO_TAG_START,
  VIDEO_TAG_END,
} from "@/lib/llm";
import {
  DATA_IMAGE_REGEX,
  DATA_VIDEO_REGEX,
  IMAGE_URL_REGEX,
  VIDEO_URL_REGEX,
} from "@/lib/images";
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

  // Wrap any raw image/video URLs or base64 data in the content with <<<image>>>...<<</image>>> / <<<video>>>...<<</video>>> tags
  // This is needed when using datasets with multimedia where mustache directly inserts media data
  const renderedContent = wrapMediaWithTags(
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
 * Wraps raw image/video URLs and base64 data URLs in the content with <<<image>>>...<<</image>>> / <<<video>>>...<<</video>>> tags.
 * Detects:
 * - data:image/...;base64,... (base64 encoded images)
 * - http(s)://... image URLs
 * - data:video/...;base64,... (base64 encoded videos)
 * - http(s)://... video URLs
 * - [image_N] / [video_N] placeholders (from processInputData)
 */
const wrapMediaWithTags = (content: string): string => {
  if (typeof content !== "string") {
    // Non-string values cannot be processed for regex replacement; fall back to empty string
    return "";
  }

  /**
   * Replacer function that wraps matches with image tags if not already wrapped.
   * Checks the surrounding context at the match position to avoid double-wrapping.
   */
  const wrapIfNotAlreadyWrapped = (
    match: string,
    offset: number,
    string: string,
    tagStart: string,
    tagEnd: string,
  ): string => {
    const prefix = string.slice(Math.max(0, offset - tagStart.length), offset);
    const suffix = string.slice(
      offset + match.length,
      offset + match.length + tagEnd.length,
    );

    // Already wrapped at this position
    if (prefix === tagStart && suffix === tagEnd) {
      return match;
    }

    return `${tagStart}${match}${tagEnd}`;
  };

  const wrapMatches = (
    input: string,
    regex: RegExp,
    tagStart: string,
    tagEnd: string,
  ) => {
    return input.replace(
      regex,
      (match, ...args: Array<string | number | undefined>) => {
        const argCount = args.length;
        const offset = args[argCount - 2] as number | undefined;
        const fullString = args[argCount - 1] as string | undefined;

        if (typeof offset !== "number" || typeof fullString !== "string") {
          return match;
        }

        return wrapIfNotAlreadyWrapped(
          match,
          offset,
          fullString,
          tagStart,
          tagEnd,
        );
      },
    );
  };

  let processedContent = content;

  processedContent = wrapMatches(
    processedContent,
    DATA_IMAGE_REGEX,
    IMAGE_TAG_START,
    IMAGE_TAG_END,
  );

  processedContent = wrapMatches(
    processedContent,
    IMAGE_URL_REGEX,
    IMAGE_TAG_START,
    IMAGE_TAG_END,
  );

  processedContent = wrapMatches(
    processedContent,
    DATA_VIDEO_REGEX,
    VIDEO_TAG_START,
    VIDEO_TAG_END,
  );

  processedContent = wrapMatches(
    processedContent,
    VIDEO_URL_REGEX,
    VIDEO_TAG_START,
    VIDEO_TAG_END,
  );

  return processedContent;
};

interface UsePromptDatasetItemCombinationArgs {
  datasetItems: DatasetItem[];
  isToStopRef: RefObject<boolean>;
  workspaceName: string;
  datasetName: string | null;
  selectedRuleIds: string[] | null;
  addAbortController: (key: string, value: AbortController) => void;
  deleteAbortController: (key: string) => void;
}

const usePromptDatasetItemCombination = ({
  datasetItems,
  isToStopRef,
  workspaceName,
  datasetName,
  selectedRuleIds,
  addAbortController,
  deleteAbortController,
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
      }
    },

    [
      isToStopRef,
      hydrateDatasetItemData,
      addAbortController,
      updateOutput,
      runStreaming,
      datasetName,
      deleteAbortController,
      selectedRuleIds,
    ],
  );

  return {
    createCombinations,
    processCombination,
  };
};

export default usePromptDatasetItemCombination;
