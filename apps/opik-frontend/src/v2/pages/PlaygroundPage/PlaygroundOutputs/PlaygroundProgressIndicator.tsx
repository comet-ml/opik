import React from "react";
import { RotateCcw } from "lucide-react";
import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";
import {
  useDatasetType,
  useProgressCompleted,
  useProgressPhase,
  useProgressTotal,
  useHasRunProgress,
  useIsAnyWatchLapsed,
} from "@/store/PlaygroundStore";
import { DATASET_TYPE } from "@/types/datasets";
import { STILL_RUNNING_REFRESH_MESSAGE } from "@/constants/experiments";

const TEST_SUITE_PHASE_LABELS: Record<string, string> = {
  running: "Step 1: Gathering LLM output",
  evaluating: "Step 2: Evaluating assertions",
};

const DATASET_PHASE_LABELS: Record<string, string> = {
  running: "Gathering LLM output",
};

interface PlaygroundProgressIndicatorProps {
  onCheckAgain: () => void;
}

const PlaygroundProgressIndicator: React.FC<
  PlaygroundProgressIndicatorProps
> = ({ onCheckAgain }) => {
  const progressTotal = useProgressTotal();
  const progressCompleted = useProgressCompleted();
  const progressPhase = useProgressPhase();
  const datasetType = useDatasetType();
  const watchLapsed = useIsAnyWatchLapsed();
  const hasRunProgress = useHasRunProgress();

  if (!hasRunProgress) {
    return null;
  }

  const progressPercentage = Math.round(
    (progressCompleted / progressTotal) * 100,
  );

  const phaseLabels =
    datasetType === DATASET_TYPE.TEST_SUITE
      ? TEST_SUITE_PHASE_LABELS
      : DATASET_PHASE_LABELS;

  const phaseLabel =
    (progressPhase && phaseLabels[progressPhase]) || "Progress";

  return (
    <div className="flex items-center gap-3">
      <div className="flex flex-1 flex-col gap-2">
        <div className="flex items-center justify-between">
          <span className="comet-body-s-accented text-foreground">
            {phaseLabel}
          </span>
          <span className="comet-body-s text-light-slate">
            {watchLapsed ? (
              STILL_RUNNING_REFRESH_MESSAGE
            ) : (
              <>
                {progressCompleted}/{progressTotal} completed (
                {progressPercentage}%)
              </>
            )}
          </span>
        </div>
        <div className="h-2 w-full rounded-full bg-secondary">
          <div
            className={cn(
              "h-2 rounded-full bg-primary transition-all duration-300",
              watchLapsed && "motion-safe:animate-pulse",
            )}
            style={{ width: `${progressPercentage}%` }}
          />
        </div>
      </div>
      {watchLapsed && (
        <Button
          variant="secondary"
          size="xs"
          className="shrink-0"
          onClick={onCheckAgain}
        >
          <RotateCcw className="mr-1 size-3.5" />
          Refresh
        </Button>
      )}
    </div>
  );
};

export default PlaygroundProgressIndicator;
