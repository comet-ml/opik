import React from "react";
import { MousePointer } from "lucide-react";

export interface RunExperimentButtonProps {
  openNewExperimentDialog: () => void;
}

const RunExperimentButton: React.FC<
  RunExperimentButtonProps & { canViewExperiments: boolean }
> = ({ canViewExperiments, openNewExperimentDialog }) => {
  if (!canViewExperiments) {
    return null;
  }

  return (
    <div
      onClick={openNewExperimentDialog}
      className="bg-background hover:shadow-action-card dark:hover:bg-primary-foreground flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border p-4 transition-shadow dark:hover:shadow-none"
    >
      <div className="bg-action-experiment-background flex size-[24px] items-center justify-center rounded">
        <MousePointer className="text-action-experiment-text size-3.5" />
      </div>
      <div className="comet-body-s">Run an experiment</div>
    </div>
  );
};

export default RunExperimentButton;
