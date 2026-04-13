import React, { useMemo } from "react";
import { AlertCircle, ArrowUpRight, Loader2, Text } from "lucide-react";

import { Button } from "@/ui/button";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import { SandboxJob, SandboxJobStatus } from "@/types/agent-sandbox";
import TraceStatsDisplay from "@/v2/pages-shared/traces/TraceStatsDisplay/TraceStatsDisplay";

type AgentRunnerResultProps = {
  job: SandboxJob | null;
  onViewTrace: () => void;
  hasTraceData?: boolean;
  duration?: number | null;
  startTime?: string;
  endTime?: string;
  totalTokens?: number;
  totalEstimatedCost?: number;
};

const AgentRunnerResult: React.FC<AgentRunnerResultProps> = ({
  job,
  onViewTrace,
  hasTraceData,
  duration,
  startTime,
  endTime,
  totalTokens,
  totalEstimatedCost,
}) => {
  const resultData = useMemo((): object | undefined => {
    if (job?.result === undefined) return undefined;
    if (typeof job.result === "object" && job.result !== null) {
      return job.result as object;
    }
    return { output: job.result };
  }, [job?.result]);

  const isCompleted = job?.status === SandboxJobStatus.COMPLETED;
  const isRunning =
    job?.status === SandboxJobStatus.RUNNING ||
    job?.status === SandboxJobStatus.PENDING;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-3 border-b px-4">
        <div className="flex items-center gap-2">
          <span className="inline-block size-3 rounded-sm bg-primary" />
          <span className="comet-body-s-accented">Result</span>
        </div>

        {isCompleted && (
          <TraceStatsDisplay
            duration={duration}
            startTime={startTime}
            endTime={endTime}
            totalTokens={totalTokens}
            estimatedCost={totalEstimatedCost}
          />
        )}

        {hasTraceData && (
          <Button
            variant="ghost"
            size="2xs"
            onClick={onViewTrace}
            className="ml-auto gap-1 p-0"
          >
            View trace
            <ArrowUpRight className="size-3.5" />
          </Button>
        )}
      </div>

      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-background">
        {!job && (
          <div className="flex flex-1 flex-col items-center justify-center gap-1 text-muted-slate">
            <Text className="mb-2 size-5 text-primary" />
            <p className="comet-body-s font-medium">No results yet</p>
            <p className="comet-body-xs mt-1 max-w-36 text-center">
              The agent response will appear here after a run
            </p>
          </div>
        )}

        {isRunning && (
          <div className="flex flex-1 flex-col items-center justify-center gap-1 text-muted-slate">
            <Loader2 className="mb-2 size-5 animate-spin text-primary" />
            <p className="comet-body-s font-medium">Your agent is working</p>
            {hasTraceData && (
              <Button variant="link" size="2xs" onClick={onViewTrace}>
                View trajectory live
              </Button>
            )}
          </div>
        )}

        {isCompleted && resultData !== undefined && (
          <SyntaxHighlighter
            data={resultData}
            prettifyConfig={{ fieldType: "output" }}
            transparent
            fullHeight
          />
        )}

        {job &&
          [SandboxJobStatus.FAILED, SandboxJobStatus.CANCELLED].includes(
            job.status,
          ) && (
            <div className="m-6 flex items-start gap-2 rounded-md border border-destructive/20 bg-destructive/5 p-4">
              <AlertCircle className="mt-0.5 size-4 shrink-0 text-destructive" />
              <div>
                <p className="comet-body-s-accented text-destructive">
                  {job.status === SandboxJobStatus.CANCELLED
                    ? "Job cancelled"
                    : "Execution failed"}
                </p>
                <p className="comet-body-xs mt-1 text-muted-slate">
                  {job.error ?? "An unknown error occurred."}
                </p>
              </div>
            </div>
          )}
      </div>
    </div>
  );
};

export default AgentRunnerResult;
