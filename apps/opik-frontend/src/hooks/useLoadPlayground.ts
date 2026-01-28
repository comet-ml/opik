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

interface NamedPromptContent {
  name: string;
  content: MessageContent;
}

interface LoadPlaygroundOptions {
  promptContent?: MessageContent;
  promptId?: string;
  promptVersionId?: string;
  autoImprove?: boolean;
  datasetId?: string;
  datasetVersionId?: string;
  templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
  namedPrompts?: NamedPromptContent[];
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

  const createPromptFromContent = useCallback(
    (
      content: MessageContent,
      options: {
        promptId?: string;
        promptVersionId?: string;
        autoImprove?: boolean;
        templateStructure?: PROMPT_TEMPLATE_STRUCTURE;
        initPrompt?: Partial<ReturnType<typeof generateDefaultPrompt>>;
      } = {},
    ) => {
      const {
        promptId,
        promptVersionId,
        autoImprove = false,
        templateStructure,
        initPrompt,
      } = options;

      const newPrompt = generateDefaultPrompt({
        initPrompt,
        setupProviders: providerKeys,
        lastPickedModel,
        providerResolver: calculateModelProvider,
        modelResolver: calculateDefaultModel,
      });

      if (templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT) {
        if (promptId) {
          newPrompt.loadedChatPromptId = promptId;
        }

        try {
          const contentString = getTextFromMessageContent(content);
          const parsedMessages = parseChatTemplateToLLMMessages(contentString, {
            promptId,
            promptVersionId,
            useTimestamp: true,
          });

          if (parsedMessages.length > 0) {
            newPrompt.messages = parsedMessages;
          } else {
            newPrompt.messages = [
              generateDefaultLLMPromptMessage({
                content,
                promptId,
                promptVersionId,
                autoImprove,
              }),
            ];
          }
        } catch (error) {
          console.error("Failed to parse chat prompt:", error);
          newPrompt.messages = [
            generateDefaultLLMPromptMessage({
              content,
              promptId,
              promptVersionId,
              autoImprove,
            }),
          ];
        }
      } else {
        newPrompt.messages = [
          generateDefaultLLMPromptMessage({
            content,
            promptId,
            promptVersionId,
            autoImprove,
          }),
        ];
      }

      return newPrompt;
    },
    [
      calculateDefaultModel,
      calculateModelProvider,
      lastPickedModel,
      providerKeys,
    ],
  );

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
        namedPrompts,
      } = options;

      let promptIds: string[];
      let promptMap: Record<string, ReturnType<typeof generateDefaultPrompt>>;

      if (namedPrompts && namedPrompts.length > 0) {
        // Multi-agent: create a separate Playground prompt for each named prompt
        const prompts = namedPrompts.map((np) =>
          createPromptFromContent(np.content, {
            templateStructure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
            initPrompt: { name: np.name },
          }),
        );
        promptIds = prompts.map((p) => p.id);
        promptMap = Object.fromEntries(prompts.map((p) => [p.id, p]));
      } else {
        const newPrompt = createPromptFromContent(promptContent, {
          promptId,
          promptVersionId,
          autoImprove,
          templateStructure,
        });
        promptIds = [newPrompt.id];
        promptMap = { [newPrompt.id]: newPrompt };
      }

      setPromptMap(promptIds, promptMap);

      if (datasetId) {
        if (isVersioningEnabled && datasetVersionId) {
          const versionKey = formatDatasetVersionKey(
            datasetId,
            datasetVersionId,
          );
          setDatasetVersionKey(versionKey);
          setDatasetId(null);
        } else {
          setDatasetId(datasetId);
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
      createPromptFromContent,
      navigate,
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
