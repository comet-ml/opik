import React, { useCallback, useEffect, useRef, useState } from "react";
import PlaygroundPrompt from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { generateDefaultPrompt } from "@/lib/playground";
import { PROVIDER_TYPE } from "@/types/providers";
import { Button } from "@/components/ui/button";
import { Plus, RotateCcw } from "lucide-react";
import { PLAYGROUND_LAST_PICKED_MODEL } from "@/constants/llm";
import {
  useAddPrompt,
  usePromptCount,
  usePromptIds,
  useSetPromptMap,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";

interface PlaygroundPromptsState {
  workspaceName: string;
  providerKeys: PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  onResetHeight: () => void;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
  onResetHeight,
}: PlaygroundPromptsState) => {
  const promptCount = usePromptCount();
  const addPrompt = useAddPrompt();
  const setPromptMap = useSetPromptMap();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const promptIds = usePromptIds();

  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
  };

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    onResetHeight();
  }, [
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
    onResetHeight,
  ]);

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
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
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
            providerResolver={calculateModelProvider}
            modelResolver={calculateDefaultModel}
          />
        ))}
      </div>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={resetPlayground}
        title="Reset playground"
        description="Resetting the Playground will discard all unsaved prompts. This action is irreversible. Do you want to proceed?"
        confirmText="Reset playground"
      />
    </>
  );
};

export default PlaygroundPrompts;
