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
    <div className="mt-auto flex min-w-full flex-col border-t">
      <div className="sticky right-0 ml-auto flex h-0 gap-2">
        <Button variant="outline" size="icon-sm" className="mt-2.5">
          <Settings2 className="size-4" />
        </Button>
        <Button
          size="sm"
          className="mt-2.5"
          onClick={() => setRunId((r) => r + 1)}
        >
          <Play className="mr-1 size-4" />
          Run
        </Button>
      </div>

      <div className="flex w-full gap-6 py-2">
        {prompts?.map((prompt, promptIdx) => (
          <PlaygroundOutput
            key={prompt.id}
            runId={runId}
            model={prompt.model}
            index={promptIdx}
            messages={prompt.messages}
          />
        ))}
      </div>
    </div>
  );
};

export default PlaygroundOutputs;
