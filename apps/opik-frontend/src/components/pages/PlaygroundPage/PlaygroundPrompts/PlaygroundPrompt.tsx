import React, { useCallback, useEffect, useRef } from "react";
import { CopyPlus, Trash } from "lucide-react";
import last from "lodash/last";

import {
  PLAYGROUND_MESSAGE_ROLE,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
} from "@/types/playground";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPlaygroundPromptMessage,
  generateDefaultPrompt,
  getDefaultConfigByProvider,
  getModelProvider,
} from "@/lib/playground";
import PlaygroundPromptMessages from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPromptMessages/PlaygroundPromptMessages";
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PromptModelSelect/PromptModelSelect";
import { getAlphabetLetter } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import PromptModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PromptModelSettings/PromptModelConfigs";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import {
  useAddPrompt,
  useDeletePrompt,
  usePromptById,
  useUpdateOutput,
  useUpdatePrompt,
} from "@/store/PlaygroundStore";
import { getDefaultProviderKey } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";

const getNextMessageType = (
  previousMessage: PlaygroundMessageType,
): PLAYGROUND_MESSAGE_ROLE => {
  if (previousMessage.role === PLAYGROUND_MESSAGE_ROLE.user) {
    return PLAYGROUND_MESSAGE_ROLE.assistant;
  }

  return PLAYGROUND_MESSAGE_ROLE.user;
};

interface PlaygroundPromptProps {
  workspaceName: string;
  index: number;
  promptId: string;
  providerKeys: PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
}

const PlaygroundPrompt = ({
  workspaceName,
  promptId,
  index,
  providerKeys,
  isPendingProviderKeys,
}: PlaygroundPromptProps) => {
  const checkedIfModelIsValidRef = useRef(false);

  const prompt = usePromptById(promptId);

  const { model, messages, configs, name } = prompt;

  const addPrompt = useAddPrompt();
  const updatePrompt = useUpdatePrompt();
  const deletePrompt = useDeletePrompt();
  const updateOutput = useUpdateOutput();

  const provider = model ? getModelProvider(model) : "";

  const handleAddMessage = useCallback(() => {
    const newMessage = generateDefaultPlaygroundPromptMessage();
    const lastMessage = last(messages);

    newMessage.role = lastMessage
      ? getNextMessageType(lastMessage!)
      : PLAYGROUND_MESSAGE_ROLE.system;

    updatePrompt(promptId, {
      messages: [...messages, newMessage],
    });
  }, [messages, updatePrompt, promptId]);

  const handleDuplicatePrompt = () => {
    const newPrompt = generateDefaultPrompt({
      initPrompt: prompt,
      setupProviders: providerKeys,
    });

    addPrompt(newPrompt, index + 1);
  };

  const handleUpdateMessage = useCallback(
    (messages: PlaygroundMessageType[]) => {
      updatePrompt(promptId, { messages });
    },
    [updatePrompt, promptId],
  );

  const handleUpdateConfig = useCallback(
    (newConfigs: Partial<PlaygroundPromptConfigsType>) => {
      updatePrompt(promptId, {
        configs: {
          ...configs,
          ...newConfigs,
        } as PlaygroundPromptConfigsType,
      });
    },
    [configs, promptId, updatePrompt],
  );

  const handleUpdateModel = useCallback(
    (model: PROVIDER_MODEL_TYPE) => {
      updatePrompt(promptId, { model });
    },
    [updatePrompt, promptId],
  );

  useEffect(() => {
    // on init, to check if a prompt has a model from valid providers: (f.e., remove a provider after setting a model)
    if (!checkedIfModelIsValidRef.current && !isPendingProviderKeys) {
      checkedIfModelIsValidRef.current = true;

      const modelProvider = model ? getModelProvider(model) : "";

      const noModelProviderWhenProviderKeysSet =
        !modelProvider && providerKeys.length > 0;
      const modelProviderIsNotFromProviderKeys =
        modelProvider && !providerKeys.includes(modelProvider);

      const needToChangeProvider =
        noModelProviderWhenProviderKeysSet ||
        modelProviderIsNotFromProviderKeys;

      if (!needToChangeProvider) {
        return;
      }

      const newProvider = getDefaultProviderKey(providerKeys);
      const newModel = newProvider ? PROVIDERS[newProvider].defaultModel : "";

      const newDefaultConfigs = newProvider
        ? getDefaultConfigByProvider(newProvider)
        : {};

      updatePrompt(promptId, {
        model: newModel,
        configs: newDefaultConfigs,
      });

      updateOutput(promptId, "", { value: "" });
    }
  }, [
    providerKeys,
    isPendingProviderKeys,
    updateOutput,
    updatePrompt,
    promptId,
    model,
  ]);

  return (
    <div className="w-full min-w-[var(--min-prompt-width)]">
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
            />
          </div>
          <PromptModelConfigs
            provider={provider}
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
              <CopyPlus className="size-3.5" />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper content="Delete a prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => deletePrompt(promptId)}
            >
              <Trash className="size-3.5" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      <PlaygroundPromptMessages
        messages={messages}
        onChange={handleUpdateMessage}
        onAddMessage={handleAddMessage}
      />
    </div>
  );
};

export default PlaygroundPrompt;
