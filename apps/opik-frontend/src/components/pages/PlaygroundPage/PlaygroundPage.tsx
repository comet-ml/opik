import React, { useCallback, useState } from "react";
import { Button } from "@/components/ui/button";
import { Plus } from "lucide-react";
import PlaygroundPrompt from "./PlaygroundPrompt/PlaygroundPrompt";
import { PlaygroundPromptType } from "@/types/playgroundPrompts";
import { generateRandomString } from "@/lib/utils";
import { generateDefaultPlaygroundPromptMessage } from "@/lib/playgroundPrompts";
import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputs";

const generateDefaultPrompt = (
  configs: Partial<PlaygroundPromptType> = {},
): PlaygroundPromptType => {
  return {
    name: "Prompt",
    messages: [generateDefaultPlaygroundPromptMessage()],
    model: "",
    ...configs,
    id: generateRandomString(),
  };
};

const PlaygroundPage = () => {
  const [prompts, setPrompts] = useState([generateDefaultPrompt()]);

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt();

    setPrompts((ps) => [...ps, newPrompt]);
  };

  const handlePromptChange = useCallback(
    (id: string, changes: Partial<PlaygroundPromptType>) => {
      setPrompts((ps) => {
        return ps.map((prompt) =>
          prompt.id !== id
            ? prompt
            : {
                ...prompt,
                ...changes,
              },
        );
      });
    },
    [],
  );

  const handlePromptRemove = useCallback((id: string) => {
    setPrompts((ps) => {
      return ps.filter((p) => p.id !== id);
    });
  }, []);

  const handlePromptDuplicate = useCallback((prompt: PlaygroundPromptType) => {
    const newPrompt = generateDefaultPrompt(prompt);

    setPrompts((ps) => [...ps, newPrompt]);
  }, []);

  return (
    <div
      className="flex h-full flex-col pt-6"
      style={{ "--min-prompt-width": "500px" } as React.CSSProperties}
    >
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Playground</h1>

        <Button
          variant="outline"
          size="sm"
          onClick={handleAddPrompt}
          className="sticky right-0"
        >
          <Plus className="mr-2 size-4" />
          Add prompt
        </Button>
      </div>

      <div className="comet-no-scrollbar mb-6 flex min-h-[50%] w-full gap-6 overflow-x-auto">
        {/*CREATE PROMPTS COMPONENT FOR CONSISTENCY ALEX*/}
        {prompts.map((prompt) => (
          <PlaygroundPrompt
            name={prompt.name}
            id={prompt.id}
            key={prompt.id}
            messages={prompt.messages}
            model={prompt.model}
            onChange={handlePromptChange}
            onClickRemove={handlePromptRemove}
            onClickDuplicate={handlePromptDuplicate}
            hideRemoveButton={prompts.length === 1}
          />
        ))}
      </div>

      <div className="mt-auto flex w-full">
        <PlaygroundOutputs prompts={prompts} />
      </div>
    </div>
  );
};

export default PlaygroundPage;
