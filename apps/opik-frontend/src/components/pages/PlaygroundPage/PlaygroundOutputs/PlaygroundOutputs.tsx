import React, { useCallback, useRef, useState } from "react";
import { Pause, Play } from "lucide-react";

import { Button } from "@/components/ui/button";
import { PlaygroundOutputType, PlaygroundPromptType } from "@/types/playground";

import PlaygroundOutput, {
  PlaygroundOutputRef,
} from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface PlaygroundOutputsProps {
  prompts: PlaygroundPromptType[];
  onChange: (id: string, changes: Partial<PlaygroundPromptType>) => void;
  workspaceName: string;
}

const PlaygroundOutputs = ({
  prompts,
  onChange,
  workspaceName,
}: PlaygroundOutputsProps) => {
  const [isRunning, setIsRunning] = useState(false);

  const outputRefs = useRef<Map<number, PlaygroundOutputRef>>(new Map());

  const areAllPromptsValid = prompts.every((p) => !!p.model);

  // a recommended by react docs way to work with ref lists
  // https://react.dev/learn/manipulating-the-dom-with-refs#how-to-manage-a-list-of-refs-using-a-ref-callback
  const getOutputRefMap = () => {
    return outputRefs.current;
  };

  const handleRunClick = async () => {
    setIsRunning(true);

    const outputMap = getOutputRefMap();
    const outputMapRefs = Array.from(outputMap.values());

    await Promise.allSettled(
      outputMapRefs.map((outputMapRef) => outputMapRef.run()),
    );

    setIsRunning(false);
  };

  const handleStopClick = () => {
    const outputMap = getOutputRefMap();
    const outputMapRefs = Array.from(outputMap.values());

    outputMapRefs.forEach((outputRef) => {
      outputRef.stop();
    });

    setIsRunning(false);
  };

  const handleOutputChange = useCallback(
    (id: string, output: PlaygroundOutputType) => {
      onChange(id, { output });
    },
    [onChange],
  );

  const renderActionButton = () => {
    if (isRunning) {
      return (
        <Button
          size="sm"
          className="mt-2.5"
          variant="outline"
          onClick={handleStopClick}
        >
          <Pause className="mr-1 size-4" />
          Stop
        </Button>
      );
    }

    const isDisabled = !areAllPromptsValid;
    const style: React.CSSProperties = isDisabled
      ? { pointerEvents: "auto" }
      : {};

    const selectLLMModelMessage =
      prompts?.length === 1
        ? "Please select a LLM model for your prompt"
        : "Please select a LLM model for your prompts";

    const runMessage =
      prompts?.length === 1 ? "Run your prompt" : "Run your prompts";

    const tooltipMessage = isDisabled ? selectLLMModelMessage : runMessage;

    return (
      <TooltipWrapper content={tooltipMessage}>
        <Button
          size="sm"
          className="mt-2.5"
          onClick={handleRunClick}
          disabled={isDisabled}
          style={style}
        >
          <Play className="mr-1 size-4" />
          Run
        </Button>
      </TooltipWrapper>
    );
  };

  return (
    <div className="mt-auto flex min-w-full flex-col border-t">
      <div className="sticky right-0 ml-auto flex h-0 gap-2">
        {renderActionButton()}
      </div>

      <div className="flex w-full gap-[var(--item-gap)] py-2">
        {prompts?.map((prompt, promptIdx) => (
          <PlaygroundOutput
            key={`output-${prompt.id}`}
            workspaceName={workspaceName}
            model={prompt.model}
            index={promptIdx}
            messages={prompt.messages}
            output={prompt.output}
            configs={prompt.configs}
            onOutputChange={(o) => handleOutputChange(prompt.id, o)}
            ref={(node) => {
              const map = getOutputRefMap();

              if (node) {
                map.set(promptIdx, node);
              }

              return () => {
                map.delete(promptIdx);
              };
            }}
          />
        ))}
      </div>
    </div>
  );
};

export default PlaygroundOutputs;
