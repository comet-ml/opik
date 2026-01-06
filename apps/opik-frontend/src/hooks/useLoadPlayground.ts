import { useCallback, useMemo } from "react";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";

import useAppStore from "@/store/AppStore";
import { usePromptMap, useSetPromptMap } from "@/store/PlaygroundStore";
import { generateDefaultPrompt } from "@/lib/playground";
import {
  generateDefaultLLMPromptMessage,
  getTextFromMessageContent,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_SELECTED_DATASET_KEY,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import useProviderKeys from "@/api/provider-keys/useProviderKeys";
import { MessageContent } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { formatDatasetVersionKey } from "@/utils/datasetVersionStorage";

interface LoadPlaygroundOptions {
  promptContent?: MessageContent;
  promptId?: string;
  promptVersionId?: string;
  autoImprove?: boolean;
  datasetId?: string;
  datasetVersionId?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
}

const useLoadPlayground = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const promptMap = usePromptMap();
  const setPromptMap = useSetPromptMap();

  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const { data: providerKeysData, isPending: isPendingProviderKeys } =
    useProviderKeys({
      workspaceName,
    });

  const isVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
  );

  const [, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  const [, setDatasetVersionKey] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    {
      defaultValue: null,
    },
  );

  const isPlaygroundEmpty = useMemo(() => {
    const keys = Object.keys(promptMap);

    return (
      keys.length === 1 &&
      promptMap[keys[0]]?.messages?.length === 1 &&
      promptMap[keys[0]]?.messages[0]?.content === ""
    );
  }, [promptMap]);

  const providerKeys = useMemo(() => {
    return providerKeysData?.content?.map((c) => c.ui_composed_provider) || [];
  }, [providerKeysData]);

  const loadPlayground = useCallback(
    (options: LoadPlaygroundOptions = {}) => {
      const {
        promptContent = "",
        promptId,
        promptVersionId,
        autoImprove = false,
        datasetId,
        datasetVersionId,
        templateStructure,
      } = options;

      const newPrompt = generateDefaultPrompt({
        setupProviders: providerKeys,
        lastPickedModel,
        providerResolver: calculateModelProvider,
        modelResolver: calculateDefaultModel,
      });

      // For chat prompts, parse the JSON and create multiple messages
      if (templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT) {
        // Set the loaded chat prompt ID for the dropdown to display
        if (promptId) {
          newPrompt.loadedChatPromptId = promptId;
        }

        try {
          const contentString = getTextFromMessageContent(promptContent);
          const parsedMessages = parseChatTemplateToLLMMessages(contentString, {
            promptId,
            promptVersionId,
            useTimestamp: true,
          });

          if (parsedMessages.length > 0) {
            newPrompt.messages = parsedMessages;
          } else {
            // Fallback to single message if parsing fails
            newPrompt.messages = [
              generateDefaultLLMPromptMessage({
                content: promptContent,
                promptId,
                promptVersionId,
                autoImprove,
              }),
            ];
          }
        } catch (error) {
          console.error("Failed to parse chat prompt:", error);
          // Fallback to single message if parsing fails, preserving full content
          newPrompt.messages = [
            generateDefaultLLMPromptMessage({
              content: promptContent,
              promptId,
              promptVersionId,
              autoImprove,
            }),
          ];
        }
      } else {
        // For text prompts, create a single message preserving full content (including media)
        newPrompt.messages = [
          generateDefaultLLMPromptMessage({
            content: promptContent,
            promptId,
            promptVersionId,
            autoImprove,
          }),
        ];
      }

      setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });

      if (datasetId) {
        if (isVersioningEnabled && datasetVersionId) {
          // Use versioned storage format: "datasetId::versionId"
          const versionKey = formatDatasetVersionKey(
            datasetId,
            datasetVersionId,
          );
          setDatasetVersionKey(versionKey);
          // Clear legacy storage to avoid conflicts
          setDatasetId(null);
        } else {
          // Use legacy storage format: "datasetId"
          setDatasetId(datasetId);
          // Clear versioned storage to avoid conflicts
          setDatasetVersionKey(null);
        }
      }

      navigate({
        to: "/$workspaceName/playground",
        params: {
          workspaceName,
        },
      });
    },
    [
      calculateDefaultModel,
      calculateModelProvider,
      lastPickedModel,
      navigate,
      providerKeys,
      setPromptMap,
      setDatasetId,
      setDatasetVersionKey,
      isVersioningEnabled,
      workspaceName,
    ],
  );

  return {
    loadPlayground,
    isPlaygroundEmpty,
    isPendingProviderKeys,
  };
};

export default useLoadPlayground;
