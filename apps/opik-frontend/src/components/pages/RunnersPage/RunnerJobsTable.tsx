import React from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { CheckCircle2, Clock, Loader2, XCircle } from "lucide-react";

import useRunnerJobsList from "@/api/runners/useRunnerJobsList";
import { RunnerJob } from "@/types/runners";
import { cn } from "@/lib/utils";

type RunnerJobsTableProps = {
  runnerId: string;
};

const STATUS_CONFIG: Record<
  string,
  { icon: React.ReactNode; label: string; className: string }
> = {
  pending: {
    icon: <Clock className="size-3.5" />,
    label: "Pending",
    className: "text-muted-slate",
  },
  running: {
    icon: <Loader2 className="size-3.5 animate-spin" />,
    label: "Running",
    className: "text-blue-600",
  },
  completed: {
    icon: <CheckCircle2 className="size-3.5" />,
    label: "Completed",
    className: "text-green-600",
  },
  failed: {
    icon: <XCircle className="size-3.5" />,
    label: "Failed",
    className: "text-destructive",
  },
};

const RunnerJobsTable: React.FunctionComponent<RunnerJobsTableProps> = ({
  runnerId,
}) => {
  const { data: jobs = [] } = useRunnerJobsList(
    { runnerId },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 2000,
    },
  );

  if (jobs.length === 0) {
    return (
      <div className="comet-body-s py-4 text-center text-muted-slate">
        No jobs yet
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-md border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b bg-muted/50">
            <th className="px-3 py-2 text-left font-medium">Status</th>
            <th className="px-3 py-2 text-left font-medium">Agent</th>
            <th className="px-3 py-2 text-left font-medium">Input</th>
            <th className="px-3 py-2 text-left font-medium">Result</th>
            <th className="px-3 py-2 text-left font-medium">Created</th>
          </tr>
        </thead>
        <tbody>
          {jobs.map((job: RunnerJob) => {
            const statusConfig = STATUS_CONFIG[job.status] || STATUS_CONFIG.pending;
            return (
              <tr key={job.id} className="border-b last:border-0">
                <td className="px-3 py-2">
                  <div
                    className={cn(
                      "flex items-center gap-1.5",
                      statusConfig.className,
                    )}
                  >
                    {statusConfig.icon}
                    <span>{statusConfig.label}</span>
                  </div>
                </td>
                <td className="px-3 py-2 font-mono text-xs">
                  {job.agent_name}
                </td>
                <td className="max-w-40 truncate px-3 py-2 font-mono text-xs">
                  {JSON.stringify(job.inputs)}
                </td>
                <td className="max-w-40 truncate px-3 py-2 font-mono text-xs">
                  {job.error ? (
                    <span className="text-destructive">{job.error}</span>
                  ) : job.result ? (
                    JSON.stringify(job.result)
                  ) : (
                    <span className="text-muted-slate">-</span>
                  )}
                </td>
                <td className="whitespace-nowrap px-3 py-2 text-xs text-muted-slate">
                  {job.created_at
                    ? new Date(job.created_at).toLocaleTimeString()
                    : "-"}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
};

export default RunnerJobsTable;
