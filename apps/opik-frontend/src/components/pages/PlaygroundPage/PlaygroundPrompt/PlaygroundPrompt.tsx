import React, { useCallback } from "react";
import { CopyPlus, Trash } from "lucide-react";
import last from "lodash/last";

import {
  PLAYGROUND_MESSAGE_ROLE,
  PlaygroundMessageType,
  PlaygroundPromptConfigsType,
  PlaygroundPromptType,
} from "@/types/playground";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPlaygroundPromptMessage,
  getModelProvider,
} from "@/lib/playground";
import PlaygroundPromptMessages from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PlaygroundPromptMessages/PlaygroundPromptMessages";
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSelect/PromptModelSelect";
import { getAlphabetLetter } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import PromptModelConfigs from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/PromptModelConfigs";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

const getNextMessageType = (
  previousMessage: PlaygroundMessageType,
): PLAYGROUND_MESSAGE_ROLE => {
  if (previousMessage.role === PLAYGROUND_MESSAGE_ROLE.user) {
    return PLAYGROUND_MESSAGE_ROLE.assistant;
  }

  return PLAYGROUND_MESSAGE_ROLE.user;
};

interface PlaygroundPromptProps extends PlaygroundPromptType {
  workspaceName: string;
  index: number;
  onChange: (id: string, changes: Partial<PlaygroundPromptType>) => void;
  onClickRemove: (id: string) => void;
  onClickDuplicate: (prompt: PlaygroundPromptType, position: number) => void;
}

const PlaygroundPrompt = ({
  workspaceName,
  index,
  onChange,
  onClickRemove,
  onClickDuplicate,
  ...prompt
}: PlaygroundPromptProps) => {
  const { name, id, messages, model, configs } = prompt;

  const provider = model ? getModelProvider(model) : "";

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
    (newConfigs: Partial<PlaygroundPromptConfigsType>) => {
      onChange(id, {
        configs: {
          ...configs,
          ...newConfigs,
        } as PlaygroundPromptConfigsType,
      });
    },
    [configs, id, onChange],
  );

  const handleUpdateModel = useCallback(
    (model: PROVIDER_MODEL_TYPE) => {
      onChange(id, { model });
    },
    [onChange, id],
  );

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
              onClick={() => onClickDuplicate(prompt, index + 1)}
            >
              <CopyPlus className="size-3.5" />
            </Button>
          </TooltipWrapper>

          <TooltipWrapper content="Delete a prompt">
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => onClickRemove(id)}
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
