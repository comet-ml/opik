import React, { useCallback, useMemo } from "react";
import {
  PLAYGROUND_MESSAGE_TYPE,
  PLAYGROUND_MODEL_TYPE,
  PlaygroundMessageType,
  PlaygroundPromptType,
} from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { CopyPlus, Trash } from "lucide-react";
import { Separator } from "@/components/ui/separator";

import {
  generateDefaultPlaygroundPromptMessage,
  getModelProvider,
} from "@/lib/playgroundPrompts";
import last from "lodash/last";
import PlaygroundPromptMessages from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PlaygroundPromptMessages/PlaygroundPromptMessages";
import PromptModelSelect from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSelect";
import PromptModelSettings from "@/components/pages/PlaygroundPage/PlaygroundPrompt/PromptModelSettings/PromptModelSettings";
import { getAlphabetLetter } from "@/lib/utils";

// ALEX CHECK LONG PROMPT NAME

interface PlaygroundPromptProps extends PlaygroundPromptType {
  index: number;
  hideRemoveButton: boolean;
  onChange: (id: string, changes: Partial<PlaygroundPromptType>) => void;
  onClickRemove: (id: string) => void;
  onClickDuplicate: (prompt: PlaygroundPromptType) => void;
}

const getNextMessageType = (
  previousMessage: PlaygroundMessageType,
): PLAYGROUND_MESSAGE_TYPE => {
  if (previousMessage.type === PLAYGROUND_MESSAGE_TYPE.user) {
    return PLAYGROUND_MESSAGE_TYPE.assistant;
  }

  return PLAYGROUND_MESSAGE_TYPE.user;
};

// ALEX ADD TOOLTIPS
// ALEX MAKE A PLACEHOLDER GRAY FOR SELECT
// ALEX MOVE PROMPT TO ANOTHER FILE
const PlaygroundPrompt = ({
  index,
  hideRemoveButton,
  onChange,
  onClickRemove,
  onClickDuplicate,
  ...prompt
}: PlaygroundPromptProps) => {
  const { name, id, messages, model } = prompt;

  // ALEX THROTTLING

  const handleAddMessage = useCallback(() => {
    const newMessage = generateDefaultPlaygroundPromptMessage();
    const lastMessage = last(messages);

    newMessage.type = lastMessage
      ? getNextMessageType(lastMessage!)
      : PLAYGROUND_MESSAGE_TYPE.system;

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

  const handleUpdateModel = useCallback(
    (model: PLAYGROUND_MODEL_TYPE) => {
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
          <div className="h-full w-72">
            <PromptModelSelect
              value={model}
              onChange={handleUpdateModel}
              provider={provider}
            />
          </div>

          <PromptModelSettings provider={provider} />

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
        onAddMessage={handleAddMessage}
      />
    </div>
  );
};

export default PlaygroundPrompt;
