import React, { useCallback, useEffect, useMemo, useRef } from "react";
import {
  PLAYGROUND_MESSAGE_ROLE,
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
  PlaygroundPromptType,
} from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { CopyPlus, Trash } from "lucide-react";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPlaygroundPromptMessage,
  getDefaultConfigByProvider,
  getModelProvider,
} from "@/lib/playgroundPrompts";
import last from "lodash/last";
import PlaygroundPromptMessages from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PlaygroundPromptMessages/PlaygroundPromptMessages";
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSelect";
import PromptModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/PromptModelSettings";
import { getAlphabetLetter } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundPromptProps extends PlaygroundPromptType {
  index: number;
  hideRemoveButton: boolean;
  onChange: (id: string, changes: Partial<PlaygroundPromptType>) => void;
  onClickRemove: (id: string) => void;
  onClickDuplicate: (prompt: PlaygroundPromptType, position: number) => void;
}

const getNextMessageType = (
  previousMessage: PlaygroundMessageType,
): PLAYGROUND_MESSAGE_ROLE => {
  if (previousMessage.role === PLAYGROUND_MESSAGE_ROLE.user) {
    return PLAYGROUND_MESSAGE_ROLE.assistant;
  }

  return PLAYGROUND_MESSAGE_ROLE.user;
};

// ALEX ADD TOOLTIPS
// ALEX MOVE PROMPT TO ANOTHER FILE
const PlaygroundPrompt = ({
  index,
  hideRemoveButton,
  onChange,
  onClickRemove,
  onClickDuplicate,
  ...prompt
}: PlaygroundPromptProps) => {
  const { name, id, messages, model, configs } = prompt;

  const provider = useMemo(() => {
    if (!model) {
      return "";
    }

    return getModelProvider(model);
  }, [model]);

  const lastProviderNameToInitializeConfig = useRef(provider);

  // ALEX THROTTLING

  const handleAddMessage = useCallback(() => {
    const newMessage = generateDefaultPlaygroundPromptMessage();
    const lastMessage = last(messages);

    newMessage.role = lastMessage
      ? getNextMessageType(lastMessage!)
      : PLAYGROUND_MESSAGE_ROLE.system;

    onChange(id, {
      messages: [...messages, newMessage],
    });
  }, [messages, onChange, id]);

  const handleUpdateMessage = useCallback(
    (messages: PlaygroundMessageType[]) => {
      onChange(id, { messages });
    },
    [onChange, id],
  );

  const handleUpdateConfig = useCallback(
    (newConfigs: PlaygroundPromptConfigsType) => {
      onChange(id, {
        configs: {
          ...configs,
          ...newConfigs,
        },
      });
    },
    [configs, id, onChange],
  );

  const handleUpdateModel = useCallback(
    (model: PLAYGROUND_MODEL_TYPE) => {
      onChange(id, { model });
    },
    [onChange, id],
  );

  useEffect(() => {
    if (lastProviderNameToInitializeConfig.current !== provider) {
      lastProviderNameToInitializeConfig.current = provider;

      if (provider) {
        handleUpdateConfig(getDefaultConfigByProvider(provider));
      }
    }
  }, [provider, configs, handleUpdateConfig]);

  return (
    <div className="w-full min-w-[var(--min-prompt-width)]">
      <div className="mb-2 flex h-8 items-center justify-between">
        <p className="comet-body-s-accented">
          {name} {getAlphabetLetter(index)}
        </p>

        <div className="flex h-full items-center justify-center gap-2">
          <div className="h-full w-72">
            <PromptModelSelect
              value={model}
              onChange={handleUpdateModel}
              provider={provider}
            />
          </div>

          {/*ALEX MAKE IT CONFIGS*/}
          <PromptModelSettings
            provider={provider}
            configs={configs}
            onChange={handleUpdateConfig}
          />

          <Separator orientation="vertical" className="h-6" />

          <TooltipWrapper content="Duplicate a prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => onClickDuplicate(prompt, index + 1)}
            >
              <CopyPlus className="size-3.5" />
            </Button>
          </TooltipWrapper>

          {!hideRemoveButton && (
            <TooltipWrapper content="Delete a prompt">
              <Button
                variant="outline"
                size="icon-sm"
                onClick={() => onClickRemove(id)}
              >
                <Trash className="size-3.5" />
              </Button>
            </TooltipWrapper>
          )}
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
