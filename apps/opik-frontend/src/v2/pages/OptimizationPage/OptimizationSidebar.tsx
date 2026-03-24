import React from "react";
import { Experiment } from "@/types/datasets";
import { Optimization, OPTIMIZATION_STATUS } from "@/types/optimizations";
import { BestPrompt } from "@/v2/pages-shared/experiments/BestPromptCard";
import BestPromptPlaceholder from "./BestPromptPlaceholder";

type ScoreData = {
  score: number;
  percentage?: number;
};

type OptimizationSidebarProps = {
  optimization: Optimization | undefined;
  bestExperiment: Experiment | undefined;
  baselineExperiment: Experiment | undefined;
  scoreMap: Record<string, ScoreData>;
  status?: OPTIMIZATION_STATUS;
};

const OptimizationSidebar: React.FC<OptimizationSidebarProps> = ({
  optimization,
  bestExperiment,
  baselineExperiment,
  scoreMap,
  status,
}) => {
  return (
    <div className="w-2/5 shrink-0 overflow-auto">
      {bestExperiment && optimization ? (
        <BestPrompt
          experiment={bestExperiment}
          optimization={optimization}
          scoreMap={scoreMap}
          baselineExperiment={baselineExperiment}
          status={status}
        />
      ) : (
        optimization?.studio_config && (
          <BestPromptPlaceholder
            objectiveName={optimization.objective_name}
            studioConfig={optimization.studio_config}
          />
        )
      )}
    </div>
  );
};

export default OptimizationSidebar;
