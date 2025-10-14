import React from "react";
import { Optimization } from "@/types/optimizations";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";
import { BestPrompt } from "@/components/pages-shared/experiments/BestPromptCard";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import OptimizationProgressChart from "./OptimizationProgressChart";

type OptimizationResultsProps = {
  optimization: Optimization | null;
};

const OptimizationResults: React.FC<OptimizationResultsProps> = ({
  optimization,
}) => {
  const { experiments } = useOptimizationStudioContext();

  const { bestExperiment, scoreMap } = useOptimizationScores(
    experiments,
    optimization?.objective_name,
  );

  return (
    <div className="relative flex gap-4">
      <OptimizationProgressChart
        optimization={optimization}
        bestExperiment={bestExperiment}
      />
      {optimization && bestExperiment ? (
        <BestPrompt
          optimization={optimization}
          experiment={bestExperiment}
          scoreMap={scoreMap}
        />
      ) : (
        <Card className="flex-1">
          <CardHeader className="px-4 py-3">
            <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4">
            <div className="comet-body-s text-muted-slate">
              No data available
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
};

export default OptimizationResults;
