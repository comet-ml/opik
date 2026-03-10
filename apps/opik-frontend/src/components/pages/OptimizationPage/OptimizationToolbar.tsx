import React from "react";
import OptimizationViewSelector, {
  OPTIMIZATION_VIEW_TYPE,
} from "@/components/pages/OptimizationPage/OptimizationViewSelector";

interface OptimizationToolbarProps {
  isStudioOptimization: boolean;
  view: OPTIMIZATION_VIEW_TYPE;
  onViewChange: (view: OPTIMIZATION_VIEW_TYPE) => void;
}

const OptimizationToolbar: React.FC<OptimizationToolbarProps> = ({
  isStudioOptimization,
  view,
  onViewChange,
}) => {
  return (
    <>
      {isStudioOptimization && (
        <OptimizationViewSelector value={view} onChange={onViewChange} />
      )}
    </>
  );
};

export default OptimizationToolbar;
