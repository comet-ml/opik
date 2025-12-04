import React, { useMemo } from "react";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";
import { useOptimizationScores } from "@/components/pages-shared/experiments/useOptimizationScores";
import { BestPrompt } from "@/components/pages-shared/experiments/BestPromptCard";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

const OptimizationResults: React.FC = () => {
  const { activeOptimization: optimization, experiments } =
    useOptimizationStudioContext();

  const { bestExperiment, scoreMap } = useOptimizationScores(
    experiments,
    optimization?.objective_name,
  );

  const baselineExperiment = useMemo(() => {
    if (!experiments.length) return undefined;
    const sortedRows = experiments
      .slice()
      .sort((e1, e2) => e1.created_at.localeCompare(e2.created_at));
    return sortedRows[0];
  }, [experiments]);

  if (optimization && bestExperiment) {
    return (
      <BestPrompt
        experiment={bestExperiment}
        optimization={optimization}
        scoreMap={scoreMap}
        baselineExperiment={baselineExperiment}
      />
    );
  }

  return (
    <Card className="h-full">
      <CardHeader className="px-4 py-3">
        <CardTitle className="comet-body-s-accented">Best prompt</CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-4">
        <div className="comet-body-s text-muted-slate">No data available</div>
      </CardContent>
    </Card>
  );
};

export default OptimizationResults;
