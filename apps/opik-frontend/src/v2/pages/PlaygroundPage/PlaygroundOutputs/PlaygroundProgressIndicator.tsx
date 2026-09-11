import React from "react";
import {
  useProgressCompleted,
  useProgressPhase,
  useProgressTotal,
} from "@/store/PlaygroundStore";

const PHASE_LABELS: Record<string, string> = {
  running: "Step 1: Gathering LLM output",
  evaluating: "Step 2: Evaluating assertions",
};

const PlaygroundProgressIndicator: React.FC = () => {
  const progressTotal = useProgressTotal();
  const progressCompleted = useProgressCompleted();
  const progressPhase = useProgressPhase();

  if (progressTotal === 0) {
    return null;
  }

  const progressPercentage = Math.round(
    (progressCompleted / progressTotal) * 100,
  );

  const phaseLabel =
    (progressPhase && PHASE_LABELS[progressPhase]) || "Progress";

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="comet-body-s-accented text-foreground">
          {phaseLabel}
        </span>
        <span className="comet-body-s text-light-slate">
          {progressCompleted}/{progressTotal} completed ({progressPercentage}%)
        </span>
      </div>
      <div className="flex flex-1 items-center">
        <div className="h-2 w-full rounded-full bg-secondary">
          <div
            className="h-2 rounded-full bg-primary transition-all duration-300"
            style={{ width: `${progressPercentage}%` }}
          />
        </div>
      </div>
    </div>
  );
};

export default PlaygroundProgressIndicator;
