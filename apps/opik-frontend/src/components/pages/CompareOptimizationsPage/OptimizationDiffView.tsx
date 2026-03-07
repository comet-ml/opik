import React from "react";

import { Experiment } from "@/types/datasets";
import ConfigurationDiffContent from "@/components/pages-shared/experiments/ConfigurationDiffContent/ConfigurationDiffContent";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

type OptimizationDiffViewProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  baselineExperiment?: Experiment | null;
  bestExperiment?: Experiment | null;
};

const OptimizationDiffView: React.FunctionComponent<
  OptimizationDiffViewProps
> = ({ open, onOpenChange, baselineExperiment, bestExperiment }) => {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[880px]">
        <DialogHeader>
          <DialogTitle>Baseline vs Best — Configuration Diff</DialogTitle>
        </DialogHeader>
        <div className="max-h-[620px] overflow-y-auto pb-2">
          <ConfigurationDiffContent
            baselineExperiment={baselineExperiment}
            currentExperiment={bestExperiment}
          />
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default OptimizationDiffView;
