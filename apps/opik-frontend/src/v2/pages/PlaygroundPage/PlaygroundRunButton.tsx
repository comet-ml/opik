import React from "react";
import { Pause, Play } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { useIsPromptRunning, usePromptById } from "@/store/PlaygroundStore";

interface PlaygroundRunButtonProps {
  promptId: string;
  onRun: () => void;
  onStop: () => void;
  className?: string;
}

const PlaygroundRunButton = ({
  promptId,
  onRun,
  onStop,
  className,
}: PlaygroundRunButtonProps) => {
  const prompt = usePromptById(promptId);
  const isPromptRunning = useIsPromptRunning(promptId);

  const hasEmptyMessages = prompt?.messages.some(
    (m) => !m.content || m.content.length === 0,
  );
  const isPromptRunDisabled = !prompt?.model || !!hasEmptyMessages;

  let promptRunDisabledReason: string | null = null;
  if (!prompt?.model) {
    promptRunDisabledReason = "Please select an LLM model for this prompt";
  } else if (hasEmptyMessages) {
    promptRunDisabledReason =
      "Message is empty. Please add some text to proceed";
  }

  return (
    <div
      className={
        className ?? "flex items-center justify-end border-b px-4 py-2"
      }
    >
      {isPromptRunning ? (
        <Button size="2xs" variant="outline" onClick={onStop}>
          <Pause className="mr-1 size-3.5" />
          Stop
        </Button>
      ) : (
        <TooltipWrapper content={promptRunDisabledReason ?? "Run this prompt"}>
          <Button
            size="2xs"
            variant="outline"
            onClick={onRun}
            disabled={isPromptRunDisabled}
            style={isPromptRunDisabled ? { pointerEvents: "auto" } : {}}
          >
            <Play className="mr-1 size-3.5" />
            Run
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default PlaygroundRunButton;
