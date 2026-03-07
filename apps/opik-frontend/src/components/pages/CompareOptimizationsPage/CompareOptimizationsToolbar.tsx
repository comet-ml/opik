import React from "react";
import OptimizationViewSelector, {
  OPTIMIZATION_VIEW_TYPE,
} from "@/components/pages/CompareOptimizationsPage/OptimizationViewSelector";

interface CompareOptimizationsToolbarProps {
  isStudioOptimization: boolean;
  view: OPTIMIZATION_VIEW_TYPE;
  onViewChange: (view: OPTIMIZATION_VIEW_TYPE) => void;
}

const CompareOptimizationsToolbar: React.FC<
  CompareOptimizationsToolbarProps
> = ({ isStudioOptimization, view, onViewChange }) => {
  return (
    <>
      {isStudioOptimization && (
        <OptimizationViewSelector value={view} onChange={onViewChange} />
      )}
    </>
  );
};

export default CompareOptimizationsToolbar;
