import React from "react";
import { Experiment } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import { BestPrompt } from "@/components/pages-shared/experiments/BestPromptCard";
import BestPromptPlaceholder from "./BestPromptPlaceholder";

type ScoreData = {
  score: number;
  percentage?: number;
};

type CompareOptimizationsSidebarProps = {
  optimization: Optimization | undefined;
  bestExperiment: Experiment | undefined;
  baselineExperiment: Experiment | undefined;
  scoreMap: Record<string, ScoreData>;
};

const CompareOptimizationsSidebar: React.FC<
  CompareOptimizationsSidebarProps
> = ({ optimization, bestExperiment, baselineExperiment, scoreMap }) => {
  return (
    <div className="max-h-[500px] w-2/5 shrink-0 overflow-auto">
      {bestExperiment && optimization ? (
        <BestPrompt
          experiment={bestExperiment}
          optimization={optimization}
          scoreMap={scoreMap}
          baselineExperiment={baselineExperiment}
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

export default CompareOptimizationsSidebar;
