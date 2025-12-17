import React from "react";
import { Button } from "@/components/ui/button";
import { Link, useNavigate } from "@tanstack/react-router";
import { useOptimizationStudioContext } from "./OptimizationStudioContext";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import useAppStore from "@/store/AppStore";
import { useLastOptimizationRun } from "@/lib/optimizationSessionStorage";

const OptimizationStudioActions: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const { activeOptimization, isSubmitting, submitOptimization } =
    useOptimizationStudioContext();
  const { clearLastSessionRunId } = useLastOptimizationRun();

  const isRunning =
    activeOptimization?.status === OPTIMIZATION_STATUS.RUNNING ||
    activeOptimization?.status === OPTIMIZATION_STATUS.INITIALIZED;
  const showViewTrials = Boolean(activeOptimization?.id);
  const showStartNew = activeOptimization && !isRunning;

  const handleStartNew = () => {
    clearLastSessionRunId();
    navigate({
      to: "/$workspaceName/optimization-studio",
      params: { workspaceName },
    });
  };

  const renderActionButton = () => {
    if (isRunning) {
      return (
        <Button size="sm" variant="outline" className="h-8" disabled>
          Stop optimization
        </Button>
      );
    }

    if (activeOptimization) {
      return null;
    }

    return (
      <Button
        size="sm"
        className="h-8"
        onClick={submitOptimization}
        disabled={isSubmitting}
      >
        {isSubmitting ? "Starting..." : "Run optimization"}
      </Button>
    );
  };

  return (
    <div className="flex gap-2">
      {showViewTrials && (
        <Button
          size="sm"
          variant={isRunning ? "default" : "outline"}
          className="h-8"
          asChild
        >
          <Link
            to="/$workspaceName/optimizations/$datasetId/compare"
            params={{
              workspaceName,
              datasetId: activeOptimization!.dataset_id,
            }}
            search={{
              optimizations: [activeOptimization!.id],
            }}
          >
            View trials
          </Link>
        </Button>
      )}
      {showStartNew && (
        <Button size="sm" className="h-8" onClick={handleStartNew}>
          Start new
        </Button>
      )}
      {renderActionButton()}
    </div>
  );
};

export default OptimizationStudioActions;
