import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Trash, Save } from "lucide-react";
import last from "lodash/last";
import { useQueryClient } from "@tanstack/react-query";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";

import { getDefaultConfigByProvider } from "@/lib/playground";
import { updateProviderConfig } from "@/lib/modelUtils";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_PROMPT_COLORS,
} from "@/constants/llm";
import { generateDefaultLLMPromptMessage, getNextMessageType } from "@/lib/llm";
import LLMPromptMessages from "@/v2/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import PromptModelSelect from "@/v2/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import { getAlphabetLetter } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import PromptModelConfigs from "@/v2/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import {
  useDatasetVariables,
  useDatasetSampleData,
  useDeletePrompt,
  usePromptById,
  usePromptCount,
  useUpdateOutput,
  useUpdatePrompt,
  useProviderValidationTrigger,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import {
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";
import { useActiveProjectId } from "@/store/AppStore";
import PromptsSelectBox from "@/v2/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import AddNewPromptVersionDialog from "@/v2/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useLoadChatPrompt from "@/hooks/useLoadChatPrompt";

interface PlaygroundPromptProps {
  workspaceName: string;
  index: number;
  promptId: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  providerResolver: ProviderResolver;
  modelResolver: ModelResolver;
}

const PlaygroundPrompt = ({
  workspaceName,
  promptId,
  index,
  providerKeys,
  isPendingProviderKeys,
  providerResolver,
  modelResolver,
}: PlaygroundPromptProps) => {
  const checkedIfModelIsValidRef = useRef(false);
  const queryClient = useQueryClient();
  const activeProjectId = useActiveProjectId();

  const prompt = usePromptById(promptId);
  const promptCount = usePromptCount();
  const datasetVariables = useDatasetVariables();
  const datasetSampleData = useDatasetSampleData();
  const providerValidationTrigger = useProviderValidationTrigger();

  const [, setLastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });

  const { model, messages, configs, name } = prompt;

  const updatePrompt = useUpdatePrompt();
  const deletePrompt = useDeletePrompt();
  const updateOutput = useUpdateOutput();

  const [showSaveChatPromptDialog, setShowSaveChatPromptDialog] =
    useState(false);
  const [lastImportedPromptName, setLastImportedPromptName] =
    useState<string>("");

  // Get the loaded chat prompt ID from the prompt data
  const selectedChatPromptId = prompt?.loadedChatPromptId;

  const handleChatPromptMessagesLoaded = useCallback(
    (newMessages: LLMMessage[], promptName: string) => {
      setLastImportedPromptName(promptName);
      updatePrompt(promptId, { messages: newMessages });
    },
    [promptId, updatePrompt],
  );

  const {
    chatPromptData,
    loadedChatPromptRef,
    chatPromptTemplate,
    hasUnsavedChatPromptChanges,
  } = useLoadChatPrompt({
    selectedChatPromptId,
    messages,
    onMessagesLoaded: handleChatPromptMessagesLoaded,
    skipInitialLoad: prompt?.skipInitialPromptLoad,
  });

  // Clear the one-time flag so it doesn't persist to localStorage
  useEffect(() => {
    if (prompt?.skipInitialPromptLoad) {
      updatePrompt(promptId, { skipInitialPromptLoad: undefined });
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const provider = providerResolver(model);

  const promptVariablesArray = useMemo(
    () => datasetVariables || [],
    [datasetVariables],
  );

  const hasMessageContent = messages.some((msg) =>
    typeof msg.content === "string"
      ? msg.content.trim()
      : Array.isArray(msg.content) && msg.content.length > 0,
  );

  const handleAddMessage = useCallback(() => {
    const newMessage = generateDefaultLLMPromptMessage();
    const lastMessage = last(messages);

    newMessage.role = lastMessage
      ? getNextMessageType(lastMessage!)
      : LLM_MESSAGE_ROLE.system;

    updatePrompt(promptId, {
      messages: [...messages, newMessage],
    });
  }, [messages, updatePrompt, promptId]);

  const handleUpdateMessage = useCallback(
    (messages: LLMMessage[]) => {
      updatePrompt(promptId, { messages });
    },
    [updatePrompt, promptId],
  );

  const handleUpdateConfig = useCallback(
    (newConfigs: Partial<LLMPromptConfigsType>) => {
      updatePrompt(promptId, {
        configs: {
          ...configs,
          ...newConfigs,
        } as LLMPromptConfigsType,
      });
    },
    [configs, promptId, updatePrompt],
  );

  const handleUpdateModel = useCallback(
    (newModel: PROVIDER_MODEL_TYPE, newProvider: COMPOSED_PROVIDER_TYPE) => {
      let newConfigs: LLMPromptConfigsType;

      if (newProvider !== provider) {
        // Provider changed: Reset to default configs for the new provider
        newConfigs = getDefaultConfigByProvider(newProvider, newModel);
      } else {
        // Model changed within same provider: Adjust existing configs if needed
        const adjustedConfigs = updateProviderConfig(configs, {
          model: newModel,
          provider: newProvider,
        });
        newConfigs = adjustedConfigs || configs;
      }

      updatePrompt(promptId, {
        model: newModel,
        provider: newProvider,
        configs: newConfigs,
      });
      setLastPickedModel(newModel);
    },
    [updatePrompt, promptId, provider, configs, setLastPickedModel],
  );

  const handleAddProvider = useCallback(
    (provider: COMPOSED_PROVIDER_TYPE) => {
      const newModel = modelResolver(model, providerKeys, provider);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updatePrompt(promptId, {
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider, newModel),
        });
      }
    },
    [
      modelResolver,
      model,
      providerKeys,
      providerResolver,
      updatePrompt,
      promptId,
    ],
  );

  const handleDeleteProvider = useCallback(() => {
    // initialize a model validation process described in the next useEffect hook, as soon as the providers list will be returned from BE
    checkedIfModelIsValidRef.current = false;
  }, []);

  useEffect(() => {
    // initialize a model validation process described in the next useEffect hook, as soon as trigger is triggered
    checkedIfModelIsValidRef.current = false;
  }, [providerValidationTrigger]);

  useEffect(() => {
    // on init, to check if a prompt has a model from valid providers: (f.e., remove a provider after setting a model)
    if (!checkedIfModelIsValidRef.current && !isPendingProviderKeys) {
      checkedIfModelIsValidRef.current = true;

      const newModel = modelResolver(model, providerKeys);

      if (newModel !== model) {
        const newProvider = providerResolver(newModel);
        updatePrompt(promptId, {
          model: newModel,
          provider: newProvider,
          configs: getDefaultConfigByProvider(newProvider, newModel),
        });

        updateOutput(promptId, "", { value: null });
      }
    }
  }, [
    providerKeys,
    isPendingProviderKeys,
    providerResolver,
    modelResolver,
    updateOutput,
    updatePrompt,
    promptId,
    model,
  ]);

  // Handler for importing chat prompt
  const handleImportChatPrompt = useCallback(
    (loadedPromptId: string) => {
      updatePrompt(promptId, { loadedChatPromptId: loadedPromptId });
    },
    [promptId, updatePrompt],
  );

  const handleDetachPrompt = useCallback(() => {
    updatePrompt(promptId, { loadedChatPromptId: undefined });
  }, [promptId, updatePrompt]);

  // Handler for saving chat prompt
  const handleSaveChatPrompt = useCallback(() => {
    setShowSaveChatPromptDialog(true);
  }, []);

  const promptColor =
    PLAYGROUND_PROMPT_COLORS[index % PLAYGROUND_PROMPT_COLORS.length];

  return (
    <div className="group/prompt flex min-w-[var(--min-prompt-width)] max-w-[var(--max-prompt-width)] flex-1 flex-col overflow-hidden border-r">
      <div className="flex h-10 items-center justify-between overflow-hidden border-b px-4">
        <div className="flex min-w-0 items-center gap-1">
          <div className="flex shrink-0 items-center gap-1 pr-2">
            <p className="comet-body-s-accented whitespace-nowrap">{name}</p>
            <span
              className="comet-body-s flex size-6 items-center justify-center rounded-md"
              style={{
                backgroundColor: promptColor.bg,
                color: promptColor.text,
              }}
            >
              {getAlphabetLetter(index)}
            </span>
          </div>
          <PromptModelSelect
            compact
            value={model}
            onChange={handleUpdateModel}
            provider={provider}
            workspaceName={workspaceName}
            onAddProvider={handleAddProvider}
            onDeleteProvider={handleDeleteProvider}
            hasError={!model}
          />
          <PromptModelConfigs
            provider={provider}
            model={model}
            configs={configs}
            onChange={handleUpdateConfig}
            size="icon-xs"
            variant="ghost"
          />
        </div>

        <div className="flex min-w-0 items-center overflow-hidden pl-4 [@media(hover:hover)]:max-w-0 [@media(hover:hover)]:pl-0 [@media(hover:hover)]:group-hover/prompt:max-w-none [@media(hover:hover)]:group-hover/prompt:pl-4">
          <PromptsSelectBox
            compact
            projectId={activeProjectId!}
            value={selectedChatPromptId}
            onValueChange={(value) => value && handleImportChatPrompt(value)}
            onClear={handleDetachPrompt}
            filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
            hasUnsavedChanges={hasUnsavedChatPromptChanges}
            promptName={chatPromptData?.name}
          />

          <div className="flex shrink-0 items-center">
            {hasMessageContent && (
              <TooltipWrapper content="Save to prompt library">
                <Button
                  variant="minimal"
                  size="icon-sm"
                  onClick={handleSaveChatPrompt}
                >
                  <Save />
                </Button>
              </TooltipWrapper>
            )}

            {promptCount > 1 && (
              <>
                <Separator orientation="vertical" className="mx-1 h-4" />
                <TooltipWrapper content="Remove prompt">
                  <Button
                    variant="minimal"
                    size="icon-sm"
                    onClick={() => deletePrompt(promptId)}
                  >
                    <Trash />
                  </Button>
                </TooltipWrapper>
              </>
            )}
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4" data-scroll-container>
        <LLMPromptMessages
          messages={messages}
          onChange={handleUpdateMessage}
          onAddMessage={handleAddMessage}
          promptVariables={promptVariablesArray}
          jsonTreeData={datasetSampleData}
          hidePromptActions={false}
          improvePromptConfig={{
            model,
            provider,
            configs,
            workspaceName,
            onAccept: (messageId, improvedContent) => {
              const updatedMessages = messages.map((msg) =>
                msg.id === messageId
                  ? { ...msg, content: improvedContent }
                  : msg,
              );
              updatePrompt(promptId, { messages: updatedMessages });
            },
          }}
        />
      </div>

      <AddNewPromptVersionDialog
        open={showSaveChatPromptDialog}
        setOpen={setShowSaveChatPromptDialog}
        prompt={chatPromptData}
        template={chatPromptTemplate}
        templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
        defaultName={lastImportedPromptName}
        onSave={(version, _, savedPromptId) => {
          setShowSaveChatPromptDialog(false);

          // Update the loaded chat prompt ID to the saved prompt
          if (savedPromptId) {
            updatePrompt(promptId, { loadedChatPromptId: savedPromptId });

            // Update the ref to mark this new version as "already loaded"
            // This prevents the useEffect from re-loading messages when we invalidate queries
            const newChatPromptKey = `${savedPromptId}-${version.id}`;
            loadedChatPromptRef.current = newChatPromptKey;

            // Invalidate the prompt queries to refetch the latest data
            // This ensures the unsaved changes indicator updates correctly
            // CRITICAL: Query keys must match the format used in the hooks (objects, not strings)
            queryClient.invalidateQueries({
              queryKey: ["prompt", { promptId: savedPromptId }],
            });
            queryClient.invalidateQueries({
              queryKey: ["prompt-version", { versionId: version.id }],
            });
          }
        }}
      />
    </div>
  );
};

export default PlaygroundPrompt;
