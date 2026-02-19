import React, { useEffect, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import {
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Clock,
  ExternalLink,
  Loader2,
  Play,
  RotateCcw,
  XCircle,
} from "lucide-react";
import { Link } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import useMyRunner, { getStoredRunnerId } from "@/api/runners/useMyRunner";
import useRunnerJobsList from "@/api/runners/useRunnerJobsList";
import useCreateRunnerJobMutation from "@/api/runners/useCreateRunnerJobMutation";
import useJobLogs from "@/api/runners/useJobLogs";
import { AgentInfo, RunnerJob } from "@/types/runners";
import { useToast } from "@/components/ui/use-toast";

type ExecutionTabProps = {
  projectId: string;
  projectName: string;
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

const COLUMN_COUNT = 7;

const JobRow: React.FunctionComponent<{
  job: RunnerJob;
  workspaceName: string;
  projectId: string;
}> = ({ job, workspaceName, projectId }) => {
  const isRunning = job.status === "running";
  const isRetryable = job.status === "completed" || job.status === "failed";
  const [expanded, setExpanded] = useState(isRunning);
  const statusConfig = STATUS_CONFIG[job.status] || STATUS_CONFIG.pending;
  const logContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isRunning) {
      setExpanded(true);
    }
  }, [isRunning]);

  const createJobMutation = useCreateRunnerJobMutation();
  const { toast } = useToast();

  const { lines } = useJobLogs({
    jobId: job.id,
    enabled: expanded,
    streaming: isRunning,
  });

  useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [lines]);

  const isPending = job.status === "pending";

  const handleRetry = (e: React.MouseEvent) => {
    e.stopPropagation();
    createJobMutation.mutate(
      {
        agent_name: job.agent_name,
        inputs:
          typeof job.inputs === "string"
            ? JSON.parse(job.inputs)
            : job.inputs,
        project: job.project,
        runner_id: job.runner_id,
      },
      {
        onSuccess: () => {
          toast({ title: "Job re-enqueued", description: `Retrying ${job.agent_name}` });
        },
      },
    );
  };

  return (
    <>
      <tr
        className={cn(
          "border-b last:border-0",
          !isPending && "cursor-pointer hover:bg-muted/30",
        )}
        onClick={
          !isPending ? () => setExpanded((v) => !v) : undefined
        }
      >
        <td className="px-3 py-2">
          <div
            className={cn(
              "flex items-center gap-1.5",
              statusConfig.className,
            )}
          >
            {!isPending &&
              (expanded ? (
                <ChevronDown className="size-3 text-muted-slate" />
              ) : (
                <ChevronRight className="size-3 text-muted-slate" />
              ))}
            {statusConfig.icon}
            <span>{statusConfig.label}</span>
          </div>
        </td>
        <td className="px-3 py-2 font-mono text-xs">{job.agent_name}</td>
        <td className="max-w-40 truncate px-3 py-2 font-mono text-xs">
          {typeof job.inputs === "string"
            ? job.inputs
            : JSON.stringify(job.inputs)}
        </td>
        <td className="px-3 py-2 font-mono text-xs">
          {job.error ? (
            <span className="whitespace-pre-wrap text-destructive">
              {job.error}
            </span>
          ) : job.result ? (
            typeof job.result === "string"
              ? job.result
              : JSON.stringify(job.result)
          ) : (
            <span className="text-muted-slate">-</span>
          )}
        </td>
        <td className="px-3 py-2 text-xs">
          {job.trace_id ? (
            <Link
              to="/$workspaceName/projects/$projectId/traces"
              params={{ workspaceName, projectId }}
              search={{
                tab: "logs",
                logsType: "traces",
                trace: job.trace_id,
              }}
              className="flex items-center gap-1 text-blue-600 hover:underline"
            >
              <ExternalLink className="size-3" />
              View
            </Link>
          ) : (
            <span className="text-muted-slate">-</span>
          )}
        </td>
        <td className="whitespace-nowrap px-3 py-2 text-xs text-muted-slate">
          {job.created_at
            ? new Date(job.created_at).toLocaleTimeString()
            : "-"}
        </td>
        <td className="px-3 py-2 text-xs">
          {isRetryable && (
            <button
              onClick={handleRetry}
              disabled={createJobMutation.isPending}
              className="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-muted-slate hover:bg-muted hover:text-foreground disabled:opacity-50"
              title="Retry job"
            >
              <RotateCcw
                className={cn(
                  "size-3.5",
                  createJobMutation.isPending && "animate-spin",
                )}
              />
            </button>
          )}
        </td>
      </tr>
      {expanded && !isPending && (
        <tr className="border-b last:border-0">
          <td colSpan={COLUMN_COUNT} className="bg-muted/20 p-0">
            <div
              ref={logContainerRef}
              className="max-h-80 overflow-auto p-3"
            >
              {lines.length > 0 ? (
                <>
                  <pre className="whitespace-pre-wrap font-mono text-xs">
                    {lines.map((line, i) => (
                      <div
                        key={i}
                        className={
                          line.stream === "stderr"
                            ? "text-destructive"
                            : "text-muted-slate"
                        }
                      >
                        {line.text}
                      </div>
                    ))}
                  </pre>
                  {job.error && (
                    <pre className="mt-2 whitespace-pre-wrap font-mono text-xs text-destructive">
                      {job.error}
                    </pre>
                  )}
                </>
              ) : (
                <>
                  {job.stdout && (
                    <pre className="mb-2 whitespace-pre-wrap font-mono text-xs text-muted-slate">
                      {job.stdout}
                    </pre>
                  )}
                  {job.error && (
                    <pre className="whitespace-pre-wrap font-mono text-xs text-destructive">
                      {job.error}
                    </pre>
                  )}
                  {isRunning && lines.length === 0 && !job.error && !job.stdout && (
                    <div className="flex items-center gap-2 text-xs text-muted-slate">
                      <Loader2 className="size-3 animate-spin" />
                      Waiting for output...
                    </div>
                  )}
                </>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
};

const FORM_FILLABLE_TYPES = new Set(["str", "int", "float", "bool"]);

const RunAgentForm: React.FunctionComponent<{
  agents: AgentInfo[];
  runnerId: string;
  projectName: string;
}> = ({ agents, runnerId, projectName }) => {
  const [selectedAgent, setSelectedAgent] = useState<string>(
    agents[0]?.name ?? "",
  );
  const [paramValues, setParamValues] = useState<Record<string, string>>({});
  const createJobMutation = useCreateRunnerJobMutation();
  const { toast } = useToast();

  const agent = agents.find((a) => a.name === selectedAgent);
  const params = agent?.params ?? [];

  const unsupportedParams = params.filter(
    (p) => !FORM_FILLABLE_TYPES.has(p.type),
  );

  const handleRun = () => {
    if (!agent) return;
    const inputs: Record<string, unknown> = {};
    for (const p of params) {
      const raw = paramValues[p.name];
      if (!raw && raw !== "0") continue;
      if (p.type === "int" || p.type === "float") {
        inputs[p.name] = Number(raw);
      } else if (p.type === "bool") {
        inputs[p.name] = raw === "true" || raw === "1";
      } else {
        inputs[p.name] = raw;
      }
    }
    createJobMutation.mutate(
      {
        agent_name: agent.name,
        inputs,
        project: projectName,
        runner_id: runnerId,
      },
      {
        onSuccess: () => {
          toast({ title: "Job created", description: `Running ${agent.name}` });
          setParamValues({});
        },
      },
    );
  };

  return (
    <div className="mb-4 rounded-md border bg-muted/20 p-3">
      <div className="mb-2 text-xs font-medium">Run Agent</div>
      {agents.length > 1 && (
        <div className="mb-2">
          <label className="mb-1 block text-xs text-muted-slate">Agent</label>
          <select
            className="rounded border bg-background px-2 py-1 text-sm"
            value={selectedAgent}
            onChange={(e) => {
              setSelectedAgent(e.target.value);
              setParamValues({});
            }}
          >
            {agents.map((a) => (
              <option key={a.name} value={a.name}>
                {a.name}
              </option>
            ))}
          </select>
        </div>
      )}
      {unsupportedParams.length > 0 ? (
        <div className="text-sm text-muted-slate">
          Cannot trigger this agent manually — parameter{" "}
          {unsupportedParams.map((p, i) => (
            <span key={p.name}>
              {i > 0 && ", "}
              <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs">
                {p.name}
              </code>{" "}
              <span className="text-xs">({p.type})</span>
            </span>
          ))}{" "}
          cannot be entered in a form. Use the Replay button on a trace instead.
        </div>
      ) : (
        <div className="flex flex-wrap items-end gap-2">
          {params.map((p) => (
            <div key={p.name}>
              <label className="mb-1 block text-xs text-muted-slate">
                {p.name}
              </label>
              <input
                type="text"
                className="rounded border bg-background px-2 py-1 text-sm"
                placeholder={p.type}
                value={paramValues[p.name] ?? ""}
                onChange={(e) =>
                  setParamValues((prev) => ({
                    ...prev,
                    [p.name]: e.target.value,
                  }))
                }
                onKeyDown={(e) => {
                  if (e.key === "Enter") handleRun();
                }}
              />
            </div>
          ))}
          <button
            onClick={handleRun}
            disabled={createJobMutation.isPending}
            className="inline-flex items-center gap-1.5 rounded bg-primary px-3 py-1 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {createJobMutation.isPending ? (
              <Loader2 className="size-3.5 animate-spin" />
            ) : (
              <Play className="size-3.5" />
            )}
            Run
          </button>
        </div>
      )}
    </div>
  );
};

const ExecutionTab: React.FunctionComponent<ExecutionTabProps> = ({
  projectId,
  projectName,
}) => {
  const workspaceName = useAppStore((s) => s.activeWorkspaceName);

  const { data: runner } = useMyRunner({
    refetchInterval: 5000,
  });

  const runnerId = getStoredRunnerId();
  const isConnected = runner?.status === "connected";

  const hasAgentForProject = runner?.agents?.some(
    (a) => a.project === projectName,
  );

  const { data: jobs = [] } = useRunnerJobsList(
    { runnerId: runnerId ?? "", project: projectName },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 2000,
      enabled: !!runnerId && isConnected,
    },
  );

  if (!isConnected) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <div className="text-sm text-muted-slate">
          Connect your machine using the status bar below to execute agents.
        </div>
      </div>
    );
  }

  if (!hasAgentForProject) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <div className="mb-1 text-sm font-medium">
          No agents for this project
        </div>
        <div className="text-sm text-muted-slate">
          Register an agent for this project using the{" "}
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">
            @entrypoint
          </code>{" "}
          decorator.
        </div>
      </div>
    );
  }

  const projectAgents = (runner?.agents ?? []).filter(
    (a) => a.project === projectName,
  );

  return (
    <div className="p-4">
      <div className="mb-3">
        <div className="font-medium">Triggered Agent Runs</div>
        <div className="font-mono text-xs text-muted-slate">
          Session {runnerId} <br></br>
        </div>
      </div>
      {projectAgents.length > 0 && runnerId && (
        <RunAgentForm
          agents={projectAgents}
          runnerId={runnerId}
          projectName={projectName}
        />
      )}
      {jobs.length === 0 ? (
        <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-slate">
          No runs yet. Use the Replay button on a trace to trigger execution.
        </div>
      ) : (
        <div className="overflow-hidden rounded-md border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-muted/50">
                <th className="px-3 py-2 text-left font-medium">Status</th>
                <th className="px-3 py-2 text-left font-medium">Agent</th>
                <th className="px-3 py-2 text-left font-medium">Input</th>
                <th className="px-3 py-2 text-left font-medium">Result</th>
                <th className="px-3 py-2 text-left font-medium">Trace</th>
                <th className="px-3 py-2 text-left font-medium">Created</th>
                <th className="w-10 px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job: RunnerJob) => (
                <JobRow
                  key={job.id}
                  job={job}
                  workspaceName={workspaceName}
                  projectId={projectId}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default ExecutionTab;
