import React, { useCallback, useEffect, useRef } from "react";
import { CopyPlus, Trash } from "lucide-react";
import last from "lodash/last";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMPromptConfigsType,
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPrompt,
  getDefaultConfigByProvider,
} from "@/lib/playground";
import { isReasoningModel } from "@/lib/modelUtils";
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

  const provider = providerResolver(model);

  const hintMessage = datasetVariables?.length
    ? `Reference dataset variables using mustache syntax: ${datasetVariables
        .map((dv) => `{{${dv}}}`)
        .join(", ")}`
    : "";

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
      // Update configs if provider changes OR if switching to/from a reasoning model
      const shouldUpdateConfigs =
        newProvider !== provider ||
        isReasoningModel(newModel) !== isReasoningModel(model);
      
      updatePrompt(promptId, {
        model: newModel,
        provider: newProvider,
        ...(shouldUpdateConfigs && {
          configs: getDefaultConfigByProvider(newProvider, newModel),
        }),
      });
      setLastPickedModel(newModel);
    },
    [updatePrompt, promptId, provider, model, setLastPickedModel],
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

  // Auto-adjust temperature for reasoning models
  useEffect(() => {
    const providerType = parseComposedProviderType(provider);
    if (
      providerType === PROVIDER_TYPE.OPEN_AI &&
      isReasoningModel(model) &&
      configs &&
      typeof (configs as LLMOpenAIConfigsType).temperature === "number" &&
      (configs as LLMOpenAIConfigsType).temperature < 1
    ) {
      updatePrompt(promptId, {
        configs: {
          ...configs,
          temperature: 1.0,
        } as LLMPromptConfigsType,
      });
    }
  }, [model, configs, provider, updatePrompt, promptId]);

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

        <div className="flex h-full items-center justify-center gap-2">
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
    </div>
  );
};

export default PlaygroundPrompt;
