import React, { useCallback, useEffect } from "react";
import PlaygroundPrompt from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import { generateDefaultPrompt } from "@/lib/playground";
import { PROVIDER_TYPE } from "@/types/providers";
import { Button } from "@/components/ui/button";
import { Plus, RotateCcw } from "lucide-react";
import {
  useAddPrompt,
  usePromptCount,
  usePromptIds,
  useSetPromptMap,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/components/pages/PlaygroundPage/PlaygroundPrompts/useLastPickedModel";

interface PlaygroundPromptsState {
  workspaceName: string;
  providerKeys: PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
}: PlaygroundPromptsState) => {
  const promptCount = usePromptCount();
  const addPrompt = useAddPrompt();
  const setPromptMap = useSetPromptMap();

  const promptIds = usePromptIds();

  const [lastPickedModel] = useLastPickedModel();

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
    });
    addPrompt(newPrompt);
  };

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
  }, [setPromptMap, providerKeys, lastPickedModel]);

  useEffect(() => {
    // hasn't been initialized yet or the last prompt is removed
    if (promptCount === 0 && !isPendingProviderKeys) {
      resetPlayground();
    }
  }, [promptCount, isPendingProviderKeys, resetPlayground]);

  return (
    <>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Playground</h1>

        <div className="sticky right-0 flex gap-2 ">
          <Button variant="outline" size="sm" onClick={resetPlayground}>
            <RotateCcw className="mr-2 size-4" />
            Reset playground
          </Button>

          <Button variant="outline" size="sm" onClick={handleAddPrompt}>
            <Plus className="mr-2 size-4" />
            Add prompt
          </Button>
        </div>
      </div>

      <div className="flex size-full gap-[var(--item-gap)]">
        {promptIds.map((promptId, idx) => (
          <PlaygroundPrompt
            workspaceName={workspaceName}
            promptId={promptId}
            index={idx}
            key={promptId}
            providerKeys={providerKeys}
            isPendingProviderKeys={isPendingProviderKeys}
          />
        ))}
      </div>
    </>
  );
};

export default PlaygroundPrompts;
