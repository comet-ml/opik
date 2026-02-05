import React from "react";
import { Link } from "@tanstack/react-router";
import { Check, X, Circle, Loader2, StopCircle, ExternalLink, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  OptimizationRun,
  OptimizationChange,
  AssertionResult,
} from "@/types/agent-intake";

type OptimizationProgressProps = {
  runs: OptimizationRun[];
  isOptimizing?: boolean;
  isComplete: boolean;
  success?: boolean;
  cancelled?: boolean;
  changes?: OptimizationChange[];
  finalAssertionResults?: AssertionResult[];
  onCancel?: () => void;
  workspaceName?: string;
  projectId?: string;
};

const OptimizationProgress: React.FC<OptimizationProgressProps> = ({
  runs,
  isOptimizing,
  isComplete,
  success,
  cancelled,
  changes,
  finalAssertionResults,
  onCancel,
  workspaceName,
  projectId,
}) => {
  const renderAssertionIcon = (
    passed: boolean | undefined,
    status: string,
  ) => {
    if (status === "running") {
      return <Circle className="size-3.5 text-muted-slate" />;
    }
    if (status === "evaluating") {
      return <Loader2 className="size-3.5 animate-spin text-muted-slate" />;
    }
    if (passed === true) {
      return <Check className="size-3.5 text-green-600" />;
    }
    if (passed === false) {
      return <X className="size-3.5 text-red-500" />;
    }
    return <Circle className="size-3.5 text-muted-slate" />;
  };

  const renderStatusBadge = (status: string) => {
    if (status === "running") {
      return (
        <span className="flex items-center gap-1.5 text-xs text-muted-slate">
          <Loader2 className="size-3 animate-spin" />
          Running...
        </span>
      );
    }
    if (status === "evaluating") {
      return (
        <span className="flex items-center gap-1.5 text-xs text-amber-600">
          <Loader2 className="size-3 animate-spin" />
          Evaluating...
        </span>
      );
    }
    if (status === "checking_regressions") {
      return (
        <span className="flex items-center gap-1.5 text-xs text-blue-600">
          <Loader2 className="size-3 animate-spin" />
          Checking regressions...
        </span>
      );
    }
    return null;
  };

  return (
    <div className="mb-4 rounded-lg border bg-background">
      <div className="flex items-center justify-between border-b px-4 py-3">
        <div className="comet-body-s-accented text-foreground">
          Optimization Progress
        </div>
        {isOptimizing && onCancel && (
          <Button
            variant="outline"
            size="sm"
            onClick={onCancel}
            className="h-7 gap-1.5 text-xs"
          >
            <StopCircle className="size-3.5" />
            Cancel
          </Button>
        )}
      </div>

      <div className="divide-y">
        {runs.map((run) => (
          <div key={`${run.label}-${run.iteration}`} className="px-4 py-3">
            <div className="mb-2 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span
                  className={cn(
                    "comet-body-s font-medium",
                    run.status === "completed" && run.all_passed && (!run.regression || run.regression.no_regressions)
                      ? "text-green-700"
                      : run.status === "completed"
                        ? "text-foreground"
                        : "text-muted-slate",
                  )}
                >
                  {run.label}
                </span>
                {run.trace_id && workspaceName && projectId && run.iteration > 0 && (
                  <Link
                    to="/$workspaceName/projects/$projectId/traces"
                    params={{ workspaceName, projectId }}
                    search={{ trace: run.trace_id }}
                    target="_blank"
                    onClick={(e) => e.stopPropagation()}
                    className="text-muted-slate hover:text-foreground"
                    title="View trace in new tab"
                  >
                    <ExternalLink className="size-3.5" />
                  </Link>
                )}
              </div>
              {run.status !== "completed" && renderStatusBadge(run.status)}
              {run.status === "completed" && (
                <span
                  className={cn(
                    "text-xs font-medium",
                    run.all_passed && (!run.regression || run.regression.no_regressions)
                      ? "text-green-600"
                      : "text-red-500",
                  )}
                >
                  {!run.all_passed
                    ? "Failed"
                    : run.regression && !run.regression.no_regressions
                      ? "Regressions found"
                      : "All passed"}
                </span>
              )}
            </div>

            <div className="space-y-1">
              {run.assertions.map((assertion, idx) => (
                <div
                  key={idx}
                  className="flex items-center gap-2 text-sm text-muted-slate"
                >
                  {renderAssertionIcon(assertion.passed, run.status)}
                  <span
                    className={cn(
                      assertion.passed === true && "text-foreground",
                      assertion.passed === false && "text-red-600",
                    )}
                  >
                    {assertion.name}
                  </span>
                </div>
              ))}
            </div>

            {run.regression && (
              run.regression.run_id && workspaceName && projectId ? (
                <Link
                  to="/$workspaceName/projects/$projectId/eval-runs/$runId"
                  params={{ workspaceName, projectId, runId: run.regression.run_id }}
                  className="mt-3 block rounded border bg-muted/20 p-2 transition-colors hover:bg-muted/40"
                >
                  <div className="flex items-center justify-between text-xs">
                    <span className="flex items-center gap-1 font-medium text-muted-slate">
                      Regression Tests
                      <ChevronRight className="size-3" />
                    </span>
                    <span className={cn(
                      "font-medium",
                      run.regression.no_regressions ? "text-green-600" : "text-red-500"
                    )}>
                      {run.regression.items_passed}/{run.regression.items_tested} passed
                    </span>
                  </div>
                  {!run.regression.no_regressions && run.regression.regressions.length > 0 && (
                    <div className="mt-2 space-y-1">
                      {run.regression.regressions.slice(0, 3).map((reg, idx) => (
                        <div key={idx} className="flex items-start gap-2 text-xs">
                          <X className="mt-0.5 size-3 shrink-0 text-red-500" />
                          <span className="text-red-600">{reg.reason}</span>
                        </div>
                      ))}
                      {run.regression.regressions.length > 3 && (
                        <div className="text-xs text-muted-slate">
                          +{run.regression.regressions.length - 3} more...
                        </div>
                      )}
                    </div>
                  )}
                </Link>
              ) : (
                <div className="mt-3 rounded border bg-muted/20 p-2">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-medium text-muted-slate">Regression Tests</span>
                    <span className={cn(
                      "font-medium",
                      run.regression.no_regressions ? "text-green-600" : "text-red-500"
                    )}>
                      {run.regression.items_passed}/{run.regression.items_tested} passed
                    </span>
                  </div>
                  {!run.regression.no_regressions && run.regression.regressions.length > 0 && (
                    <div className="mt-2 space-y-1">
                      {run.regression.regressions.map((reg, idx) => (
                        <div key={idx} className="flex items-start gap-2 text-xs">
                          <X className="mt-0.5 size-3 shrink-0 text-red-500" />
                          <span className="text-red-600">{reg.reason}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )
            )}
          </div>
        ))}
      </div>

      {isComplete && (
        <div
          className={cn(
            "border-t px-4 py-3",
            success ? "bg-green-50" : cancelled ? "bg-muted/50" : "bg-red-50",
          )}
        >
          <div
            className={cn(
              "comet-body-s-accented mb-1",
              success ? "text-green-800" : cancelled ? "text-muted-foreground" : "text-red-800",
            )}
          >
            {success ? "Optimization Complete!" : cancelled ? "Optimization Cancelled" : "Optimization Did Not Converge"}
          </div>
          {!success && !cancelled && (
            <div className="comet-body-s mb-2 text-red-700">
              The optimizer could not find a configuration that passes all assertions.
            </div>
          )}
          {changes && changes.length > 0 && (
            <ul className="mt-2 space-y-1">
              {changes.map((change, idx) => (
                <li key={idx} className="flex items-start gap-2 text-sm">
                  <Check className="mt-0.5 size-3.5 shrink-0 text-green-600" />
                  <span className="text-green-800">{change.description}</span>
                </li>
              ))}
            </ul>
          )}
          {!success && !cancelled && finalAssertionResults && finalAssertionResults.length > 0 && (
            <div className="mt-3">
              <div className="comet-body-xs mb-2 font-medium text-red-800">
                Final assertion results:
              </div>
              <div className="space-y-1">
                {finalAssertionResults.map((assertion, idx) => (
                  <div
                    key={idx}
                    className="flex items-center gap-2 text-sm"
                  >
                    {assertion.passed ? (
                      <Check className="size-3.5 text-green-600" />
                    ) : (
                      <X className="size-3.5 text-red-500" />
                    )}
                    <span
                      className={cn(
                        assertion.passed ? "text-green-800" : "text-red-700",
                      )}
                    >
                      {assertion.name}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default OptimizationProgress;
