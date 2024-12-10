import React, { useCallback, useState } from "react";
import {
  PLAYGROUND_MESSAGE_TYPE,
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
  PlaygroundPromptType,
} from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { CopyPlus, Plus, Trash } from "lucide-react";
import { Separator } from "@/components/ui/separator";

import { generateDefaultPlaygroundPromptMessage } from "@/lib/playgroundPrompts";
import last from "lodash/last";
import PlaygroundPromptMessages from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PlaygroundPromptMessages/PlaygroundPromptMessages";
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSelect";
import PromptModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings";

// ALEX CLEAN SELECT AFTER IT"S USED
// ALEX CHECK LONG PROMPT NAME
// ALEX CODE SNIPPET REMOVE THE POINTER

interface PlaygroundPromptProps extends PlaygroundPromptType {
  hideRemoveButton: boolean;
  onChange: (id: string, changes: Partial<PlaygroundPromptType>) => void;
  onClickRemove: (id: string) => void;
  onClickDuplicate: (prompt: PlaygroundPromptType) => void;
}

const getNextMessageType = (
  previousMessage: PlaygroundMessageType,
): PLAYGROUND_MESSAGE_TYPE => {
  if (previousMessage.type === PLAYGROUND_MESSAGE_TYPE.user) {
    return PLAYGROUND_MESSAGE_TYPE.system;
  }

  return PLAYGROUND_MESSAGE_TYPE.user;
};

// ALEX ADD TOOLTIPS
// ALEX ADD ICON TO THE SELECT
// ALEX MAKE A PLACEHOLDER GRAY FOR SELECT
// ALEX MOVE PROMPT TO ANOTHER FILE
const PlaygroundPrompt = ({
  hideRemoveButton,
  onChange,
  onClickRemove,
  onClickDuplicate,
  ...prompt
}: PlaygroundPromptProps) => {
  const { name, id, messages, model } = prompt;

  // ALEX THROTTLING

  const handleAddMessage = () => {
    const newMessage = generateDefaultPlaygroundPromptMessage();
    const lastMessage = last(messages);
    newMessage.type = Boolean(lastMessage)
      ? getNextMessageType(lastMessage!)
      : PLAYGROUND_MESSAGE_TYPE.system;

    onChange(id, {
      messages: [...messages, newMessage],
    });
  };

  const handleUpdateMessage = useCallback(
    (messages: PlaygroundMessageType[]) => {
      onChange(id, { messages });
    },
    [messages, onChange],
  );

  const handleUpdateModel = useCallback(
    (model: PLAYGROUND_MODEL_TYPE) => {
      onChange(id, { model });
    },
    [messages, onChange],
  );

  return (
    <div className="w-full min-w-[var(--min-prompt-width)]">
      <div className="flex items-center justify-between mb-2 h-8">
        <p className="comet-body-s">{name}</p>

        <div className="flex h-full gap-2 items-center justify-center">
          <div className="w-60 h-full">
            <PromptModelSelect value={model} onChange={handleUpdateModel} />
          </div>

          <PromptModelSettings model={model} />

          <Separator orientation="vertical" className="h-6" />

          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => onClickDuplicate(prompt)}
          >
            <CopyPlus className="size-3.5" />
          </Button>

          {!hideRemoveButton && (
            <Button
              variant="outline"
              size="icon-sm"
              onClick={() => onClickRemove(id)}
            >
              <Trash className="size-3.5" />
            </Button>
          )}
        </div>
      </div>

      {/*ALEX MAKE A SEPARATE COMPONENT*/}
      <PlaygroundPromptMessages
        messages={messages}
        onChange={handleUpdateMessage}
      />

      <Button
        variant="outline"
        size="sm"
        className="mt-2"
        onClick={handleAddMessage}
      >
        <Plus className="mr-2 size-4" />
        Message
      </Button>
    </div>
  );
};

export default PlaygroundPrompt;
