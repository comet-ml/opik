import React from "react";
import { Button } from "@/components/ui/button";
import { useNavigate } from "@tanstack/react-router";
import { useOptimizationStudioContext } from "./OptimizationStudioContext";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import useAppStore from "@/store/AppStore";

const OptimizationStudioActions: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { activeOptimization } = useOptimizationStudioContext();

  const handleViewTrials = () => {
    if (!activeOptimization) return;

    navigate({
      to: "/$workspaceName/optimizations/$datasetId/$optimizationId/compare",
      params: {
        workspaceName,
        datasetId: activeOptimization.dataset_id,
        optimizationId: activeOptimization.id,
      },
      search: {
        optimizations: [activeOptimization.id],
      },
    });
  };

  if (!activeOptimization) {
    return null;
  }

  // Optimization in progress - show "Stop optimization" (outlined) + "View trials" (primary)
  if (activeOptimization.status === OPTIMIZATION_STATUS.RUNNING) {
    return (
      <div className="flex gap-2">
        <Button size="sm" variant="outline" className="h-8">
          Stop optimization
        </Button>
        <Button size="sm" className="h-8" onClick={handleViewTrials}>
          View trials
        </Button>
      </div>
    );
  }

  // Optimization completed - show "View trials" (outlined) + "New optimization" (primary)
  return (
    <div className="flex gap-2">
      <Button
        size="sm"
        variant="outline"
        className="h-8"
        onClick={handleViewTrials}
      >
        View trials
      </Button>
    </div>
  );
};

export default OptimizationStudioActions;
