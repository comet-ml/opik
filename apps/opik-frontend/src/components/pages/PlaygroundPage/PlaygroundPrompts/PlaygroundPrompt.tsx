import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { CopyPlus, Trash, Save } from "lucide-react";
import last from "lodash/last";
import { useQueryClient } from "@tanstack/react-query";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
} from "@/types/providers";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPrompt,
  getDefaultConfigByProvider,
} from "@/lib/playground";
import { updateProviderConfig } from "@/lib/modelUtils";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import { generateDefaultLLMPromptMessage, getNextMessageType } from "@/lib/llm";
import LLMPromptMessages from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessages";
import PromptModelSelect from "@/components/pages-shared/llm/PromptModelSelect/PromptModelSelect";
import { getAlphabetLetter } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import PromptModelConfigs from "@/components/pages-shared/llm/PromptModelSettings/PromptModelConfigs";
import {
  useAddPrompt,
  useDatasetVariables,
  useDeletePrompt,
  usePromptById,
  useUpdateOutput,
  useUpdatePrompt,
  useProviderValidationTrigger,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import {
  ModelResolver,
  ProviderResolver,
} from "@/hooks/useLLMProviderModelsData";
import PromptsSelectBox from "@/components/pages-shared/llm/PromptsSelectBox/PromptsSelectBox";
import AddNewPromptVersionDialog from "@/components/pages-shared/llm/LLMPromptMessages/AddNewPromptVersionDialog";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import useLoadChatPrompt from "@/hooks/useLoadChatPrompt";
import { PromptLibraryMetadata } from "@/types/playground";

// Helper to safely parse template JSON string
const parseTemplateJson = (template: string | undefined): unknown => {
  if (!template) return null;
  try {
    return JSON.parse(template);
  } catch {
    return template; // Return as-is if not valid JSON
  }
};

interface PlaygroundPromptProps {
  workspaceName: string;
  index: number;
  promptId: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  providerResolver: ProviderResolver;
  modelResolver: ModelResolver;
  scrollToPromptRef: React.MutableRefObject<string>;
}

const PlaygroundPrompt = ({
  workspaceName,
  promptId,
  index,
  providerKeys,
  isPendingProviderKeys,
  providerResolver,
  modelResolver,
  scrollToPromptRef,
}: PlaygroundPromptProps) => {
  const checkedIfModelIsValidRef = useRef(false);
  const queryClient = useQueryClient();

  const prompt = usePromptById(promptId);
  const datasetVariables = useDatasetVariables();
  const providerValidationTrigger = useProviderValidationTrigger();

  const [, setLastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });

  const { model, messages, configs, name } = prompt;

  const addPrompt = useAddPrompt();
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
    chatPromptVersionData,
    loadedChatPromptRef,
    chatPromptTemplate,
    hasUnsavedChatPromptChanges,
  } = useLoadChatPrompt({
    selectedChatPromptId,
    messages,
    onMessagesLoaded: handleChatPromptMessagesLoaded,
  });

  // Store promptLibraryMetadata when a chat prompt is successfully loaded and unchanged
  useEffect(() => {
    // Set metadata when prompt is loaded from library and unchanged
    if (
      selectedChatPromptId &&
      chatPromptData &&
      chatPromptVersionData &&
      chatPromptData.id === selectedChatPromptId &&
      !hasUnsavedChatPromptChanges
    ) {
      const metadata: PromptLibraryMetadata = {
        name: chatPromptData.name,
        id: chatPromptData.id,
        version: {
          template: parseTemplateJson(chatPromptVersionData.template),
          id: chatPromptVersionData.id,
          ...(chatPromptVersionData.commit && {
            commit: chatPromptVersionData.commit,
          }),
          ...(chatPromptVersionData.metadata && {
            metadata: chatPromptVersionData.metadata,
          }),
        },
      };
      // Only update if metadata is different to avoid infinite loops
      if (
        !prompt?.promptLibraryMetadata ||
        prompt.promptLibraryMetadata.version.id !== metadata.version.id
      ) {
        updatePrompt(promptId, { promptLibraryMetadata: metadata });
      }
    }

    // Clear metadata when prompt has unsaved changes (was edited)
    if (hasUnsavedChatPromptChanges && prompt?.promptLibraryMetadata) {
      updatePrompt(promptId, { promptLibraryMetadata: undefined });
    }

    // Clear metadata when no chat prompt is selected
    if (!selectedChatPromptId && prompt?.promptLibraryMetadata) {
      updatePrompt(promptId, { promptLibraryMetadata: undefined });
    }
  }, [
    selectedChatPromptId,
    chatPromptData,
    chatPromptVersionData,
    hasUnsavedChatPromptChanges,
    promptId,
    updatePrompt,
    prompt?.promptLibraryMetadata,
  ]);

  const provider = providerResolver(model);

  const promptVariablesArray = useMemo(
    () => datasetVariables || [],
    [datasetVariables],
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

  const handleDuplicatePrompt = () => {
    const newPrompt = generateDefaultPrompt({
      initPrompt: prompt,
      setupProviders: providerKeys,
      providerResolver: providerResolver,
      modelResolver: modelResolver,
    });

    addPrompt(newPrompt, index + 1);
    scrollToPromptRef.current = newPrompt.id;
  };

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

        updateOutput(promptId, "", { value: "" });
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

  // Handler for saving chat prompt
  const handleSaveChatPrompt = useCallback(() => {
    setShowSaveChatPromptDialog(true);
  }, []);

  const setRef = useCallback(
    (element: HTMLDivElement | null) => {
      if (element && scrollToPromptRef.current === promptId) {
        element?.scrollIntoView({
          behavior: "smooth",
          inline: "start",
        });
      }
    },
    [promptId, scrollToPromptRef],
  );

  return (
    <div
      className="h-[var(--prompt-height)] w-full min-w-[var(--min-prompt-width)]"
      style={
        {
          "--prompt-height": "calc(100% - 64px)",
        } as React.CSSProperties
      }
      ref={setRef}
    >
      <div className="mb-2 flex h-8 items-center justify-between">
        <p className="comet-body-s-accented">
          {name} {getAlphabetLetter(index)}
        </p>

        <div className="flex h-full flex-1 items-center justify-end gap-1">
          <TooltipWrapper content={chatPromptData?.name || "Load chat prompt"}>
            <div className="flex h-full min-w-40 max-w-60 flex-auto flex-nowrap">
              <PromptsSelectBox
                value={selectedChatPromptId}
                onValueChange={(value) =>
                  value && handleImportChatPrompt(value)
                }
                clearable={false}
                filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
              />
            </div>
          </TooltipWrapper>
          <TooltipWrapper
            content={
              hasUnsavedChatPromptChanges
                ? "This prompt version hasn't been saved"
                : "Save as chat prompt"
            }
          >
            <Button
              variant="outline"
              size="icon-sm"
              onClick={handleSaveChatPrompt}
              badge={hasUnsavedChatPromptChanges}
            >
              <Save />
            </Button>
          </TooltipWrapper>
          <Separator orientation="vertical" className="h-6" />
          <div className="h-full w-80">
            <PromptModelSelect
              value={model}
              onChange={handleUpdateModel}
              provider={provider}
              workspaceName={workspaceName}
              onAddProvider={handleAddProvider}
              onDeleteProvider={handleDeleteProvider}
              hasError={!model}
            />
          </div>
          <PromptModelConfigs
            provider={provider}
            model={model}
            configs={configs}
            onChange={handleUpdateConfig}
          />
          <Separator orientation="vertical" className="h-6" />
          <TooltipWrapper content="Duplicate a prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={handleDuplicatePrompt}
            >
              <CopyPlus />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper content="Delete a prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => deletePrompt(promptId)}
            >
              <Trash />
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      <LLMPromptMessages
        messages={messages}
        onChange={handleUpdateMessage}
        onAddMessage={handleAddMessage}
        promptVariables={promptVariablesArray}
        hidePromptActions={false}
        improvePromptConfig={{
          model,
          provider,
          configs,
          workspaceName,
          onAccept: (messageId, improvedContent) => {
            const updatedMessages = messages.map((msg) =>
              msg.id === messageId ? { ...msg, content: improvedContent } : msg,
            );
            updatePrompt(promptId, { messages: updatedMessages });
          },
        }}
      />

      <AddNewPromptVersionDialog
        open={showSaveChatPromptDialog}
        setOpen={setShowSaveChatPromptDialog}
        prompt={chatPromptData}
        template={chatPromptTemplate}
        templateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
        defaultName={lastImportedPromptName}
        onSave={(version, promptName, savedPromptId) => {
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
