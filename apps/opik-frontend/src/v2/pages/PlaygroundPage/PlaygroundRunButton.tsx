import React from "react";
import { Pause, Play } from "lucide-react";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import {
  useDatasetType,
  useIsPromptRunning,
  useIsResumingRun,
  usePromptById,
  useDatasetItemsTotal,
} from "@/store/PlaygroundStore";
import { canRunMessages, hasUnsupportedMedia } from "@/lib/playground";
import { usePlaygroundDataset } from "@/hooks/usePlaygroundDataset";
import { DATASET_TYPE } from "@/types/datasets";

interface PlaygroundRunButtonProps {
  promptId: string;
  onRun: () => void;
  onStop: () => void;
  className?: string;
}

const PlaygroundRunButton = ({
  promptId,
  onRun,
  onStop,
  className,
}: PlaygroundRunButtonProps) => {
  const prompt = usePromptById(promptId);
  const isPromptRunning = useIsPromptRunning(promptId);
  const isResumingRun = useIsResumingRun();

  const { datasetId, itemsTotal } = usePlaygroundDataset();
  const datasetType = useDatasetType();
  const datasetItemsTotal = useDatasetItemsTotal();

  const hasUnrunnableMessages = !!prompt && !canRunMessages(prompt.messages);
  const hasMediaCompatibilityIssue = !!prompt && hasUnsupportedMedia(prompt);
  const isDatasetEmpty = !!datasetId && itemsTotal === 0;
  // Zero rows is a real state, not a loading one: a filter can exclude everything
  const hasNoDatasetItems = !!datasetId && datasetItemsTotal === 0;
  const isPromptRunDisabled =
    isResumingRun ||
    !prompt?.model ||
    hasUnrunnableMessages ||
    hasMediaCompatibilityIssue ||
    isDatasetEmpty ||
    hasNoDatasetItems;

  let promptRunDisabledReason: string | null = null;
  if (!prompt?.model) {
    promptRunDisabledReason = "Please select an LLM model for this prompt";
  } else if (hasUnrunnableMessages) {
    promptRunDisabledReason =
      "Message is empty. Please add some text to proceed";
  } else if (hasMediaCompatibilityIssue) {
    promptRunDisabledReason =
      "This prompt contains media but the selected model doesn't support media input";
  } else if (isDatasetEmpty) {
    const datasetLabel =
      datasetType === DATASET_TYPE.TEST_SUITE ? "test suite" : "dataset";
    promptRunDisabledReason = `This ${datasetLabel} version has no items. Add items or select another version to run an experiment`;
  } else if (hasNoDatasetItems) {
    promptRunDisabledReason = "No dataset items match the current filters";
  }

  return (
    <div
      className={
        className ?? "flex items-center justify-end border-b px-4 py-2"
      }
    >
      {isPromptRunning ? (
        <Button size="2xs" variant="outline" onClick={onStop}>
          <Pause className="mr-1 size-3.5" />
          Stop
        </Button>
      ) : (
        <TooltipWrapper content={promptRunDisabledReason ?? "Run this prompt"}>
          <Button
            size="2xs"
            variant="outline"
            onClick={onRun}
            disabled={isPromptRunDisabled}
            style={isPromptRunDisabled ? { pointerEvents: "auto" } : {}}
          >
            <Play className="mr-1 size-3.5" />
            Run
          </Button>
        </TooltipWrapper>
      )}
    </div>
  );
};

export default PlaygroundRunButton;
