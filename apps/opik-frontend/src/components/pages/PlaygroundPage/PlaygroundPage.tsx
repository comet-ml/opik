import React, { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Plus } from "lucide-react";
import PlaygroundPrompt from "./PlaygroundPrompt/PlaygroundPrompt";
import {
  PlaygroundOutputType,
  PlaygroundPromptType,
} from "@/types/playgroundPrompts";
import { generateRandomString } from "@/lib/utils";
import { generateDefaultPlaygroundPromptMessage } from "@/lib/playgroundPrompts";
import PlaygroundOutputs from "@/components/pages/PlaygroundPage/PlaygroundOutputs";

const generateDefaultPrompt = (
  configs: Partial<PlaygroundPromptType> = {},
): PlaygroundPromptType => {
  return {
    name: "Prompt",
    messages: [generateDefaultPlaygroundPromptMessage()],
    ...configs,

    id: generateRandomString(),
    model: "",
  };
};

const generateOutputForPrompt = (
  prompt: PlaygroundPromptType,
): PlaygroundOutputType => {
  return {
    id: generateRandomString(),
    text: generateRandomString(4000),
    promptId: prompt.id,
  };
};

// ALEX ADD PADDING TO THE PAGE

const PlaygroundPage = () => {
  const [prompts, setPrompts] = useState([generateDefaultPrompt()]);
  const [outputs, setOutputs] = useState<PlaygroundOutputType[]>(() => {
    return prompts.map(generateOutputForPrompt);
  });

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt();

    setPrompts((ps) => [...ps, newPrompt]);
    setOutputs((os) => [...os, generateOutputForPrompt(newPrompt)]);
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
    setOutputs((os) => {
      return os.filter((o) => o.promptId !== id);
    });
  }, []);

  const handlePromptDuplicate = useCallback((prompt: PlaygroundPromptType) => {
    const newPrompt = generateDefaultPrompt(prompt);

    setPrompts((ps) => [...ps, newPrompt]);
    setOutputs((os) => [...os, generateOutputForPrompt(newPrompt)]);
  }, []);

  // ALEX
  // MAKE ADD PROMPT STICKY
  return (
    <div
      className="flex h-full w-fit min-w-full flex-col pt-6"
      style={{ "--min-prompt-width": "500px" } as React.CSSProperties}
    >
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Playground</h1>

        <Button variant="outline" size="sm" onClick={handleAddPrompt}>
          <Plus className="mr-2 size-4" />
          Add prompt
        </Button>
      </div>

      <div className="mb-6 flex min-h-[50%] w-full gap-6">
        {/*CREATE PROMPTS COMPONENT FOR CONSISTENCY ALEX*/}
        {prompts.map((prompt, idx) => (
          <PlaygroundPrompt
            index={idx}
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

      <PlaygroundOutputs prompts={prompts} />
    </div>
  );
};

export default PlaygroundPage;
