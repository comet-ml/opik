import React, { useState } from "react";
import { CheckCircle2, XCircle, ExternalLink, ChevronDown, ChevronRight } from "lucide-react";
import { Link } from "@tanstack/react-router";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { EvalRun, EvalRunResult, useEvalRunResults } from "@/api/config/useEvalSuites";
import { cn } from "@/lib/utils";

type RunResultsDialogProps = {
  run: EvalRun | null;
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

const ResultCard: React.FC<{
  result: EvalRunResult;
  projectId: string;
  workspaceName: string;
}> = ({ result, projectId, workspaceName }) => {
  const [expanded, setExpanded] = useState(!result.passed);
  const failedAssertions = result.assertion_results.filter((a) => !a.passed);
  const passedAssertions = result.assertion_results.filter((a) => a.passed);
  const hasDetails = failedAssertions.length > 0 || result.error_message;

  return (
    <div
      className={cn(
        "rounded-lg border",
        result.passed ? "border-border bg-card" : "border-red-200 bg-red-50/50"
      )}
    >
      <div
        className={cn(
          "flex items-center gap-3 px-4 py-3",
          hasDetails && "cursor-pointer"
        )}
        onClick={() => hasDetails && setExpanded(!expanded)}
      >
        {hasDetails ? (
          expanded ? (
            <ChevronDown className="size-4 shrink-0 text-muted-slate" />
          ) : (
            <ChevronRight className="size-4 shrink-0 text-muted-slate" />
          )
        ) : (
          <div className="size-4 shrink-0" />
        )}

        {result.passed ? (
          <CheckCircle2 className="size-5 shrink-0 text-green-500" />
        ) : (
          <XCircle className="size-5 shrink-0 text-destructive" />
        )}

        <div className="min-w-0 flex-1">
          <code className="block truncate text-sm">
            {JSON.stringify(result.input_data)}
          </code>
        </div>

        <div className="flex shrink-0 items-center gap-4">
          <span className="text-sm text-muted-slate">
            {passedAssertions.length}/{result.assertion_results.length} assertions
          </span>

          {result.duration_ms && (
            <span className="text-sm text-muted-slate">{result.duration_ms}ms</span>
          )}

          {result.trace_id && (
            <Link
              to="/$workspaceName/projects/$projectId/traces"
              params={{ workspaceName, projectId }}
              search={{ trace: result.trace_id }}
              target="_blank"
              onClick={(e) => e.stopPropagation()}
              className="flex items-center gap-1 text-sm text-primary hover:underline"
            >
              Trace <ExternalLink className="size-3" />
            </Link>
          )}
        </div>
      </div>

      {expanded && hasDetails && (
        <div className="border-t border-red-200 bg-red-50/80 px-4 py-3">
          <div className="ml-8 space-y-3">
            {failedAssertions.length > 0 && (
              <div>
                <div className="mb-2 text-xs font-medium uppercase tracking-wide text-destructive">
                  Failed Assertions
                </div>
                <div className="space-y-1.5">
                  {failedAssertions.map((assertion, idx) => (
                    <div key={idx} className="flex items-start gap-2">
                      <XCircle className="mt-0.5 size-4 shrink-0 text-destructive" />
                      <span className="text-sm text-destructive">{assertion.name}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {result.error_message && (
              <div>
                <div className="mb-2 text-xs font-medium uppercase tracking-wide text-destructive">
                  Error
                </div>
                <pre className="whitespace-pre-wrap rounded bg-destructive/10 px-3 py-2 text-sm text-destructive">
                  {result.error_message}
                </pre>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
};

const RunResultsDialog: React.FC<RunResultsDialogProps> = ({
  run,
  projectId,
  open,
  onOpenChange,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: results, isPending } = useEvalRunResults({
    runId: open ? run?.id ?? null : null,
  });

  const passedCount = results?.filter((r) => r.passed).length ?? 0;
  const failedCount = (results?.length ?? 0) - passedCount;
  const totalCount = results?.length ?? 0;
  const passRate = totalCount > 0 ? Math.round((passedCount / totalCount) * 100) : 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>Run Results</DialogTitle>
        </DialogHeader>

        {run && (
          <div className="flex items-center gap-6 border-b pb-4">
            <div className="flex items-center gap-2">
              <div
                className={cn(
                  "size-2 rounded-full",
                  run.status === "completed" ? "bg-green-500" :
                  run.status === "failed" ? "bg-destructive" : "bg-muted-slate"
                )}
              />
              <span className="text-sm font-medium">
                {run.status.charAt(0).toUpperCase() + run.status.slice(1)}
              </span>
            </div>
            <div className="h-4 w-px bg-border" />
            <div className="flex items-center gap-4 text-sm">
              <span className="text-green-600">{passedCount} passed</span>
              <span className="text-destructive">{failedCount} failed</span>
              <span className="text-muted-slate">{passRate}% pass rate</span>
            </div>
          </div>
        )}

        <DialogAutoScrollBody className="space-y-2 pt-2">
          {isPending ? (
            <div className="flex justify-center py-8">
              <Loader />
            </div>
          ) : results && results.length > 0 ? (
            results.map((result) => (
              <ResultCard
                key={result.id}
                result={result}
                projectId={projectId}
                workspaceName={workspaceName}
              />
            ))
          ) : (
            <div className="py-8 text-center text-sm text-muted-slate">
              No results recorded for this run.
            </div>
          )}
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default RunResultsDialog;
