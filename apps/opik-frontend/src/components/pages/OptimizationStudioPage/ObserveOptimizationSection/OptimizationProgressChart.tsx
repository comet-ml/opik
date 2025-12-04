import React from "react";
import { Optimization } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import OptimizationProgressChartContainer from "@/components/pages-shared/experiments/OptimizationProgressChart";
import { useOptimizationStudioContext } from "../OptimizationStudioContext";

type OptimizationProgressChartProps = {
  optimization: Optimization | null;
  bestExperiment?: Experiment;
};

const OptimizationProgressChart: React.FC<OptimizationProgressChartProps> = ({
  optimization,
  bestExperiment,
}) => {
  const { experiments } = useOptimizationStudioContext();

  return (
    <OptimizationProgressChartContainer
      experiments={experiments}
      bestEntityId={bestExperiment?.id}
      objectiveName={optimization?.objective_name}
    />
  );
};

export default OptimizationProgressChart;
