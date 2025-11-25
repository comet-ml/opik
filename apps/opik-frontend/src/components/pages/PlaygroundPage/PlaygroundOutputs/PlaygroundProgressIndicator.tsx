import React from "react";
import {
  useProgressCompleted,
  useProgressTotal,
  useIsThrottlingActive,
} from "@/store/PlaygroundStore";

const PlaygroundProgressIndicator: React.FC = () => {
  const progressTotal = useProgressTotal();
  const progressCompleted = useProgressCompleted();
  const isThrottlingActive = useIsThrottlingActive();

  if (progressTotal === 0) {
    return null;
  }

  const progressPercentage =
    progressTotal > 0
      ? Math.round((progressCompleted / progressTotal) * 100)
      : 0;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="comet-body-s-accented text-foreground">Progress</span>
        <div className="flex items-center gap-2">
          {isThrottlingActive && (
            <div className="flex items-center gap-1.5 text-muted-foreground">
              <div className="size-1.5 animate-pulse rounded-full bg-muted-foreground" />
              <span className="comet-body-xs italic">Throttling...</span>
            </div>
          )}
          <span className="comet-body-s text-light-slate">
            {progressCompleted}/{progressTotal} completed ({progressPercentage}
            %)
          </span>
        </div>
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
