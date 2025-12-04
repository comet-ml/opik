import React from "react";
import OptimizationResults from "./OptimizationResults";
import OptimizationLogs from "./OptimizationLogs";
import OptimizationProgressChart from "./OptimizationProgressChart";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";

const ObserveOptimizationSection: React.FC = () => {
  const { activeOptimization, experiments } = useOptimizationStudioContext();

  const { bestExperiment } = useOptimizationScores(
    experiments,
    activeOptimization?.objective_name,
  );

  return (
    <div className="flex flex-col gap-4">
      <OptimizationProgressChart
        optimization={activeOptimization}
        bestExperiment={bestExperiment}
      />

      <div className="flex min-h-[400px] gap-4">
        <div className="flex-1">
          <OptimizationLogs optimization={activeOptimization} />
        </div>

        <div className="flex-1">
          <OptimizationResults />
        </div>
      </div>
    </div>
  );
};

export default ObserveOptimizationSection;
