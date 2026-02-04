import React from "react";
import { CheckCircle2, XCircle, ExternalLink } from "lucide-react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import { EvalRun, EvalRunResult, useEvalRunResults } from "@/api/config/useEvalSuites";

type RunResultsDialogProps = {
  run: EvalRun | null;
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

const PassIcon: React.FC<{ passed: boolean }> = ({ passed }) =>
  passed ? (
    <CheckCircle2 className="size-4 text-green-500" />
  ) : (
    <XCircle className="size-4 text-destructive" />
  );

const ResultRow: React.FC<{ result: EvalRunResult; projectId: string }> = ({
  result,
  projectId,
}) => {
  return (
    <div className="rounded-md border bg-card p-4">
      <div className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <PassIcon passed={result.passed} />
          <span className="font-medium">
            {result.passed ? "Passed" : "Failed"}
          </span>
          {result.duration_ms && (
            <span className="text-sm text-muted-slate">
              ({result.duration_ms}ms)
            </span>
          )}
        </div>
        {result.trace_id && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() =>
              window.open(
                `/${projectId}/traces?traceId=${result.trace_id}`,
                "_blank"
              )
            }
          >
            <ExternalLink className="mr-1.5 size-3.5" />
            View Trace
          </Button>
        )}
      </div>

      <div className="mb-3">
        <div className="mb-1 text-xs font-medium text-muted-slate">Input</div>
        <code className="block truncate rounded bg-muted px-2 py-1 text-xs">
          {JSON.stringify(result.input_data)}
        </code>
      </div>

      <div>
        <div className="mb-1 text-xs font-medium text-muted-slate">
          Assertions
        </div>
        <div className="space-y-1">
          {result.assertion_results.map((assertion, idx) => (
            <div key={idx} className="flex items-center gap-2">
              <PassIcon passed={assertion.passed} />
              <span className="text-sm">{assertion.name}</span>
            </div>
          ))}
        </div>
      </div>

      {result.error_message && (
        <div className="mt-3">
          <div className="mb-1 text-xs font-medium text-destructive">Error</div>
          <code className="block rounded bg-destructive/10 px-2 py-1 text-xs text-destructive">
            {result.error_message}
          </code>
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
  const { data: results, isPending } = useEvalRunResults({
    runId: open ? run?.id ?? null : null,
  });

  const passedCount = results?.filter((r) => r.passed).length ?? 0;
  const totalCount = results?.length ?? 0;
  const passRate = totalCount > 0 ? Math.round((passedCount / totalCount) * 100) : 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Run Results</DialogTitle>
          {run && (
            <div className="flex items-center gap-3 text-sm text-muted-slate">
              <span
                className={
                  run.status === "completed"
                    ? "text-green-500"
                    : run.status === "failed"
                      ? "text-destructive"
                      : ""
                }
              >
                {run.status.charAt(0).toUpperCase() + run.status.slice(1)}
              </span>
              <span>•</span>
              <span>
                {passedCount}/{totalCount} passed ({passRate}%)
              </span>
            </div>
          )}
        </DialogHeader>

        <DialogAutoScrollBody className="space-y-3">
          {isPending ? (
            <div className="flex justify-center py-8">
              <Loader />
            </div>
          ) : results && results.length > 0 ? (
            results.map((result) => (
              <ResultRow
                key={result.id}
                result={result}
                projectId={projectId}
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
