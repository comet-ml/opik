import { useCallback, useEffect, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Separator } from "@/ui/separator";
import PlaygroundPrompt from "@/v2/pages/PlaygroundPage/PlaygroundPrompts/PlaygroundPrompt";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { generateDefaultPrompt } from "@/lib/playground";
import { COMPOSED_PROVIDER_TYPE } from "@/types/providers";
import { Button } from "@/ui/button";
import { RotateCcw } from "lucide-react";
import {
  PLAYGROUND_LAST_PICKED_MODEL,
  PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
} from "@/constants/llm";
import {
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
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface PlaygroundPromptsState {
  workspaceName: string;
  providerKeys: COMPOSED_PROVIDER_TYPE[];
  isPendingProviderKeys: boolean;
}

const PlaygroundPrompts = ({
  workspaceName,
  providerKeys,
  isPendingProviderKeys,
}: PlaygroundPromptsState) => {
  const promptCount = usePromptCount();
  const setPromptMap = useSetPromptMap();
  const clearCreatedExperiments = useClearCreatedExperiments();
  const setSelectedRuleIds = useSetSelectedRuleIds();
  const setIsRunning = useSetIsRunning();
  const resetDatasetFilters = useResetDatasetFilters();
  const setDatasetVariables = useSetDatasetVariables();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const promptIds = usePromptIds();
  const [lastPickedModel] = useLastPickedModel({
    key: PLAYGROUND_LAST_PICKED_MODEL,
  });
  const { calculateModelProvider, calculateDefaultModel } =
    useLLMProviderModelsData();

  const [, setDatasetVersionKey] = useLocalStorageState<string | null>(
    PLAYGROUND_SELECTED_DATASET_VERSION_KEY,
    {
      defaultValue: null,
    },
  );
  const resetPlayground = useCallback(() => {
    const newPrompt = generateDefaultPrompt({
      setupProviders: providerKeys,
      lastPickedModel,
      providerResolver: calculateModelProvider,
      modelResolver: calculateDefaultModel,
    });
    setPromptMap([newPrompt.id], { [newPrompt.id]: newPrompt });
    setDatasetVersionKey(null);
    setSelectedRuleIds(null);
    clearCreatedExperiments();
    setIsRunning(false);
    resetDatasetFilters();
    setDatasetVariables([]);
  }, [
    providerKeys,
    lastPickedModel,
    calculateModelProvider,
    calculateDefaultModel,
    setPromptMap,
    setDatasetVersionKey,
    setSelectedRuleIds,
    clearCreatedExperiments,
    setIsRunning,
    resetDatasetFilters,
    setDatasetVariables,
  ]);

  useEffect(() => {
    // hasn't been initialized yet or the last prompt is removed
    if (promptCount === 0 && !isPendingProviderKeys) {
      resetPlayground();
    }
  }, [promptCount, isPendingProviderKeys, resetPlayground]);

  return (
    <div className="flex h-[50vh] shrink-0 flex-col border-b">
      <div className="flex items-center justify-between p-4">
        <div className="flex items-center gap-1">
          <h1 className="comet-title-l">Playground</h1>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_playground]}
          />
        </div>
        <div className="sticky right-4">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              resetKeyRef.current += 1;
              setOpen(true);
            }}
          >
            <RotateCcw className="mr-2 size-4" />
            Reset
          </Button>
        </div>
      </div>

      <Separator />

      <div className="flex min-h-0 flex-1">
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
        description="Resetting the Playground will discard all unsaved prompts. This action can't be undone. Are you sure you want to continue?"
        confirmText="Reset playground"
      />
    </div>
  );
};

export default PlaygroundPrompts;
