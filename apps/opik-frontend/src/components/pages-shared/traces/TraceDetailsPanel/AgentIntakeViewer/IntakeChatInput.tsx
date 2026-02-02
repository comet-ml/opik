import React, { useCallback } from "react";
import { cn } from "@/lib/utils";
import TextareaAutosize from "react-textarea-autosize";
import { Play, Square } from "lucide-react";

import { TEXT_AREA_CLASSES } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { INPUT_HINT } from "@/types/agent-intake";

const RUN_HOT_KEYS = ["⌘", "⏎"];

type IntakeChatInputProps = {
  value: string;
  isRunning: boolean;
  inputHint: INPUT_HINT;
  onValueChange: (value: string) => void;
  onSend: (value: string) => void;
};

const IntakeChatInput: React.FC<IntakeChatInputProps> = ({
  value,
  isRunning,
  inputHint,
  onValueChange,
  onSend,
}) => {
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
        event.preventDefault();
        event.stopPropagation();
        if (value.trim()) {
          onSend(value.trim());
        }
      }
    },
    [onSend, value],
  );

  const handleButtonClick = useCallback(() => {
    if (!isRunning && value.trim()) {
      onSend(value.trim());
    }
  }, [isRunning, value, onSend]);

  const minRows = inputHint === INPUT_HINT.textarea ? 5 : 2;
  const placeholder =
    inputHint === INPUT_HINT.textarea
      ? "Describe what the agent should do..."
      : "Type your message";

  return (
    <div className="min-w-72 max-w-full">
      <div className="relative">
        <TextareaAutosize
          placeholder={placeholder}
          value={value}
          onChange={(event) => onValueChange(event.target.value)}
          onKeyDown={handleKeyDown}
          className={cn(TEXT_AREA_CLASSES, "min-h-12 pr-10 leading-none")}
          minRows={minRows}
          maxRows={10}
          disabled={isRunning}
        />
        <TooltipWrapper
          content={isRunning ? "Processing..." : "Send message"}
          hotkeys={!value.trim() ? undefined : RUN_HOT_KEYS}
        >
          <Button
            size="icon-sm"
            className="absolute bottom-2 right-2"
            onClick={handleButtonClick}
            disabled={!value.trim() || isRunning}
          >
            {isRunning ? <Square /> : <Play />}
          </Button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default IntakeChatInput;
