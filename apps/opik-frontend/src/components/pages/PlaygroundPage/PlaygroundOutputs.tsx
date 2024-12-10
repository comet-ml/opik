import React from "react";
import { PlaygroundOutputType } from "@/types/playgroundPrompts";
import { Button } from "@/components/ui/button";
import { Play, Settings2 } from "lucide-react";

interface PlaygroundOutputsProps {
  outputs: PlaygroundOutputType[];
}

const PlaygroundOutputs = ({ outputs }: PlaygroundOutputsProps) => {
  return (
    <div className="min-h-[100px]">
      <div className="flex items-center justify-between">
        <p className="comet-body-s my-3">Output</p>

        <div className="flex al gap-2">
          <Button variant="outline" size="icon-sm">
            <Settings2 className="size-4" />
          </Button>
          <Button size="sm">
            <Play className="size-4 mr-1" />
            Run
          </Button>
        </div>
      </div>

      <div className="flex gap-6">
        {outputs.map((output) => (
          <div key={output.id} className="min-w-[var(--min-prompt-width)]">
            <p className="break-all bg-white border p-3 rounded comet-body-s ">
              {output.text}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
};

export default PlaygroundOutputs;
