import React, { useState } from "react";
import { PlaygroundPromptType } from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { Play, Settings2 } from "lucide-react";
import PlaygroundOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";

interface PlaygroundOutputsProps {
  prompts: PlaygroundPromptType[];
}

const PlaygroundOutputs = ({ prompts }: PlaygroundOutputsProps) => {
  const [runId, setRunId] = useState(0);

  return (
    <div className="min-h-[150px] min-w-full border-t">
      <div className="flex w-full items-center justify-between">
        <p className="comet-body-s my-3">Output</p>

        <div className="flex gap-2">
          <Button variant="outline" size="icon-sm">
            <Settings2 className="size-4" />
          </Button>
          <Button size="sm" onClick={() => setRunId((r) => r + 1)}>
            <Play className="mr-1 size-4" />
            Run
          </Button>
        </div>
      </div>

      <div className="flex w-full gap-6 overflow-x-auto py-2">
        {prompts?.map((prompt) => (
          <PlaygroundOutput
            key={prompt.id}
            runId={runId}
            model={prompt.model}
            promptId={prompt.id}
            messages={prompt.messages}
          />
        ))}
      </div>
    </div>
  );
};

export default PlaygroundOutputs;
