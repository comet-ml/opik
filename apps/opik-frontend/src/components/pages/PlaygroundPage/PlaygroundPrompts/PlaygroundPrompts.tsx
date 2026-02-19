import React, { useCallback, useEffect, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import PlaygroundPrompt from "@/components/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { generateDefaultPrompt } from "@/lib/playground";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { Button } from "@/components/ui/button";
import { Plus, RotateCcw } from "lucide-react";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_SELECTED_DATASET_KEY,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";
import {
  useAddPrompt,
  usePromptCount,
  usePromptIds,
  useSetIsRunning,
  useSetPromptMap,
  useClearCreatedExperiments,
  useSetSelectedRuleIds,
  useResetDatasetFilters,
  useSetDatasetVariables,
} from "@/store/PlaygroundStore";
import useLastPickedModel from "@/hooks/useLastPickedModel";
import useLLMProviderModelsData from "@/hooks/useLLMProviderModelsData";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface PlaygroundPromptsState {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
  onResetHeight: () => void;
  hasDataset: boolean;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
  onResetHeight,
  hasDataset,
}: PlaygroundPromptsState) => {
  const promptCount = usePromptCount();
  const addPrompt = useAddPrompt();
  const setPromptMap = useSetPromptMap();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const setIsRunning = useSetIsRunning();
  const resetDatasetFilters = useResetDatasetFilters();
  const setDatasetVariables = useSetDatasetVariables();
  const resetKeyRef = useRef(0);
  const scrollToPromptRef = useRef<string>("");
  const [open, setOpen] = useState<boolean>(false);

  const promptIds = usePromptIds();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [, setDatasetId] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_KEY,
    {
      defaultValue: null,
    },
  );

  const [, setDatasetVersionKey] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    {
      defaultValue: null,
    },
  );

  const handleAddPrompt = () => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    addPrompt(newPrompt);
    scrollToPromptRef.current = newPrompt.id;
  };

  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    setDatasetId(null);
    setDatasetVersionKey(null);
    setSelectedRuleIds(null);
    clearCreatedExperiments();
    setIsRunning(false);
    resetDatasetFilters();
    setDatasetVariables([]);
    onResetHeight();
  }, [
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
    setDatasetId,
    setDatasetVersionKey,
    setSelectedRuleIds,
    clearCreatedExperiments,
    setIsRunning,
    resetDatasetFilters,
    setDatasetVariables,
    onResetHeight,
  ]);

  useEffect(() => {
    // hasn't been initialized yet or the last prompt is removed
    if (promptCount === 0 && !isPendingProviderKeys) {
      resetPlayground();
    }
  }, [promptCount, isPendingProviderKeys, resetPlayground]);

  return (
    <div className="flex h-full flex-col">
      <div className="mb-4 flex items-center justify-between">
        <div className="flex items-center gap-1">
          <h1 className="comet-title-l">Playground</h1>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_playground]}
          />
        </div>

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

      <div
        className={`flex size-full gap-[var(--item-gap)] ${
          hasDataset ? "h-auto min-h-0 flex-1 overflow-x-auto" : ""
        }`}
      >
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
            scrollToPromptRef={scrollToPromptRef}
          />
        ))}
      </div>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={resetPlayground}
        title="Reset playground"
        description="Resetting the Playground will discard all unsaved prompts. This action can't be undone. Are you sure you want to continue?"
        confirmText="Reset playground"
      />
    </div>
  );
};

export default PlaygroundPrompts;
