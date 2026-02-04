import React from "react";
import { CheckCircle2, XCircle, Loader2, Clock } from "lucide-react";
import { formatDistanceToNow } from "date-fns";

import { Button } from "@/components/ui/button";
import { EvalRun, EvalRunStatus } from "@/api/config/useEvalSuites";

type RunHistoryListProps = {
  runs: EvalRun[];
  onViewRun: (runId: string) => void;
};

const StatusIcon: React.FC<{ status: EvalRunStatus }> = ({ status }) => {
  switch (status) {
    case "completed":
      return <CheckCircle2 className="size-4 text-green-500" />;
    case "failed":
      return <XCircle className="size-4 text-destructive" />;
    case "running":
      return <Loader2 className="size-4 animate-spin text-blue-500" />;
    case "pending":
    default:
      return <Clock className="size-4 text-muted-slate" />;
  }
};

const formatPassRate = (run: EvalRun): string => {
  if (run.total_items === 0) return "—";
  const pct = Math.round(run.pass_rate * 100);
  return `${run.passed_items}/${run.total_items} passed (${pct}%)`;
};

const formatTime = (dateStr: string | null): string => {
  if (!dateStr) return "—";
  try {
    return formatDistanceToNow(new Date(dateStr), { addSuffix: true });
  } catch {
    return dateStr;
  }
};

const RunHistoryList: React.FC<RunHistoryListProps> = ({ runs, onViewRun }) => {
  if (runs.length === 0) {
    return (
      <div className="rounded-md border border-dashed p-4 text-center text-sm text-muted-slate">
        No runs yet. Runs are created when you execute the evaluation suite.
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {runs.map((run, index) => (
        <div
          key={run.id}
          className="flex items-center justify-between rounded-md border bg-card px-4 py-3"
        >
          <div className="flex items-center gap-3">
            <StatusIcon status={run.status} />
            <div>
              <div className="flex items-center gap-2">
                <span className="font-medium">Run #{runs.length - index}</span>
                <span className="text-sm text-muted-slate">
                  {formatTime(run.created_at)}
                </span>
              </div>
              <div className="text-sm text-muted-slate">
                {run.status === "pending" && "Pending..."}
                {run.status === "running" && "Running..."}
                {run.status === "completed" && formatPassRate(run)}
                {run.status === "failed" && "Failed"}
              </div>
            </div>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onViewRun(run.id)}
            disabled={run.status === "pending"}
          >
            View
          </Button>
        </div>
      ))}
    </div>
  );
};

export default RunHistoryList;
