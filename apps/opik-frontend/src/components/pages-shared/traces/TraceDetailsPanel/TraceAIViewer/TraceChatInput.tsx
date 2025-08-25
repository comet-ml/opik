import React, { useCallback } from "react";
import { cn } from "@/lib/utils";
import TextareaAutosize from "react-textarea-autosize";
import { Info, Play, Square } from "lucide-react";

import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { TraceLLMChatType } from "@/types/ai-assistant";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

const RUN_HOT_KEYS = ["⌘", "⏎"];

type TraceChatInputProps = {
  chat: TraceLLMChatType;
  isRunning: boolean;
  onUpdateChat: (changes: Partial<TraceLLMChatType>) => void;
  onButtonClick: () => void;
};

const TraceChatInput: React.FC<TraceChatInputProps> = ({
  chat,
  isRunning,
  onUpdateChat,
  onButtonClick,
}) => {
  const { value } = chat;
  const isDisabledButton = !value && !isRunning;

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
        event.preventDefault();
        event.stopPropagation();

        onButtonClick();
      }
    },
    [onButtonClick],
  );

  return (
    <div className="min-w-72 max-w-full">
      <div className="relative">
        <TextareaAutosize
          placeholder="Type your message"
          value={value}
          onChange={(event) => onUpdateChat({ value: event.target.value })}
          onKeyDown={handleKeyDown}
          className={cn(TEXT_AREA_CLASSES, "min-h-12 leading-none pr-10")}
          minRows={5}
          maxRows={10}
        />
        <TooltipWrapper
          content={isRunning ? "Stop chat" : "Send message"}
          hotkeys={isDisabledButton ? undefined : RUN_HOT_KEYS}
        >
          <Button
            size="icon-sm"
            className="absolute bottom-2 right-2"
            onClick={onButtonClick}
            disabled={isDisabledButton}
          >
            {isRunning ? <Square /> : <Play />}
          </Button>
        </TooltipWrapper>
      </div>

      <div className="comet-body-xs relative mt-2 pl-4 text-light-slate">
        <Info className="absolute left-0 top-0.5 size-3 shrink-0" />
        {EXPLAINERS_MAP[EXPLAINER_ID.trace_opik_ai].description}
      </div>
    </div>
  );
};

export default TraceChatInput;
