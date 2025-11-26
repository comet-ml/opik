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
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import usePromptByIdApi from "@/api/prompts/usePromptById";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

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

  // Fetch chat prompt data when selected
  const { data: chatPromptData, isSuccess: chatPromptDataLoaded } =
    usePromptByIdApi(
      {
        promptId: selectedChatPromptId!,
      },
      {
        enabled: !!selectedChatPromptId,
      },
    );

  // Fetch chat prompt version when chat prompt is loaded
  const {
    data: chatPromptVersionData,
    isSuccess: chatPromptVersionDataLoaded,
  } = usePromptVersionById(
    {
      versionId: chatPromptData?.latest_version?.id || "",
    },
    {
      enabled: !!chatPromptData?.latest_version?.id && chatPromptDataLoaded,
    },
  );

  const provider = providerResolver(model);

  const hintMessage = datasetVariables?.length
    ? `Reference dataset variables using mustache syntax: ${datasetVariables
        .map((dv) => `{{${dv}}}`)
        .join(", ")}`
    : "";

  // Memoize the template JSON to avoid costly JSON.stringify on every render
  const chatPromptTemplate = useMemo(
    () =>
      JSON.stringify(
        messages.map((msg) => ({ role: msg.role, content: msg.content })),
      ),
    [messages],
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
      updatePrompt(promptId, {
        model: newModel,
        provider: newProvider,
        ...(newProvider !== provider && {
          configs: getDefaultConfigByProvider(newProvider, newModel),
        }),
      });
      setLastPickedModel(newModel);
    },
    [updatePrompt, promptId, provider, setLastPickedModel],
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
    (loadedPromptId?: string) => {
      if (loadedPromptId) {
        updatePrompt(promptId, { loadedChatPromptId: loadedPromptId });
      }
    },
    [promptId, updatePrompt],
  );

  // Effect to populate messages when chat prompt data is loaded
  useEffect(() => {
    if (
      chatPromptVersionData?.template &&
      selectedChatPromptId &&
      chatPromptData &&
      chatPromptVersionDataLoaded
    ) {
      try {
        // Parse the JSON string from template
        const parsedMessages = JSON.parse(chatPromptVersionData.template);

        // Convert to LLMMessage format - this will OVERWRITE existing messages
        const newMessages: LLMMessage[] = parsedMessages.map(
          (msg: { role: string; content: unknown }) =>
            generateDefaultLLMPromptMessage({
              role: msg.role as LLM_MESSAGE_ROLE,
              content: msg.content as LLMMessage["content"],
            }),
        );

        // Save the imported prompt name for later use when saving
        setLastImportedPromptName(chatPromptData.name);

        // Update the prompt with new messages (overwrites existing)
        updatePrompt(promptId, { messages: newMessages });
      } catch (error) {
        console.error("Failed to parse chat prompt:", error);
      }
    }
  }, [
    chatPromptVersionData,
    promptId,
    updatePrompt,
    selectedChatPromptId,
    chatPromptData,
    chatPromptVersionDataLoaded,
  ]);

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
                onValueChange={handleImportChatPrompt}
                clearable={false}
                filterByTemplateStructure={PROMPT_TEMPLATE_STRUCTURE.CHAT}
              />
            </div>
          </TooltipWrapper>
          <TooltipWrapper content="Save as chat prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={handleSaveChatPrompt}
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
        hint={hintMessage}
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
        onSave={() => {
          setShowSaveChatPromptDialog(false);
          // Invalidate prompt queries to ensure the latest version is selected
          queryClient.invalidateQueries({ queryKey: ["prompts"] });
          queryClient.invalidateQueries({ queryKey: ["prompt-versions"] });
        }}
      />
    </div>
  );
};

export default PlaygroundPrompt;
