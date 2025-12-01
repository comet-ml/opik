import React from "react";
import { Button } from "@/components/ui/button";
import { Link } from "@tanstack/react-router";
import { useFormContext } from "react-hook-form";
import { useOptimizationStudioContext } from "./OptimizationStudioContext";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import useAppStore from "@/store/AppStore";

const OptimizationStudioActions: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { activeOptimization, isSubmitting, submitOptimization } =
    useOptimizationStudioContext();
  const form = useFormContext();
  const isFormValid = form.formState.isValid;

  const isRunning = activeOptimization?.status === OPTIMIZATION_STATUS.RUNNING;
  const showViewTrials = Boolean(activeOptimization?.id);

  const renderActionButton = () => {
    if (isRunning) {
      return (
        <Button size="sm" variant="outline" className="h-8">
          Stop optimization
        </Button>
      );
    }

    return (
      <Button
        size="sm"
        className="h-8"
        onClick={submitOptimization}
        disabled={!isFormValid || isSubmitting}
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
      {renderActionButton()}
    </div>
  );
};

export default OptimizationStudioActions;
