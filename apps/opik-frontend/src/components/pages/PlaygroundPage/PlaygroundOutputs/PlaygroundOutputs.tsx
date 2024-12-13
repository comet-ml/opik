import React, { useRef, useState } from "react";
import { PlaygroundPromptType } from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { Pause, Play } from "lucide-react";
import PlaygroundOutput, {
  PlaygroundOutputRef,
} from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";

interface PlaygroundOutputsProps {
  prompts: PlaygroundPromptType[];
}

const PlaygroundOutputs = ({ prompts }: PlaygroundOutputsProps) => {
  const [isRunning, setIsRunning] = useState(false);

  const outputRefs = useRef<Map<number, PlaygroundOutputRef>>(new Map());

  // a recommended by react docs way to work with ref lists
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

    return (
      <Button size="sm" className="mt-2.5" onClick={handleRunClick}>
        <Play className="mr-1 size-4" />
        Run
      </Button>
    );
  };

  return (
    <div className="mt-auto flex min-w-full flex-col border-t">
      <div className="sticky right-0 ml-auto flex h-0 gap-2">
        {renderActionButton()}
      </div>

      <div className="flex w-full gap-6 py-2">
        {prompts?.map((prompt, promptIdx) => (
          <PlaygroundOutput
            key={prompt.id}
            model={prompt.model}
            index={promptIdx}
            messages={prompt.messages}
            configs={prompt.configs}
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
