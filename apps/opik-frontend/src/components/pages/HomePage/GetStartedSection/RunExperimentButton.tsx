import React from "react";
import { MousePointer } from "lucide-react";
import { WithPermissionsProps } from "@/types/permissions";

export interface RunExperimentButtonProps {
  openNewExperimentDialog: () => void;
}

const RunExperimentButton: React.FC<
  RunExperimentButtonProps & WithPermissionsProps
> = ({ canViewExperiments, openNewExperimentDialog }) => {
  if (!canViewExperiments) {
    return null;
  }

  return (
    <div
      onClick={openNewExperimentDialog}
      className="flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border bg-background p-4 transition-shadow hover:shadow-action-card dark:hover:bg-primary-foreground dark:hover:shadow-none"
    >
      <div className="flex size-[24px] items-center justify-center rounded bg-action-experiment-background">
        <MousePointer className="size-3.5 text-action-experiment-text" />
      </div>
      <div className="comet-body-s">Run an experiment</div>
    </div>
  );
};

export default RunExperimentButton;
