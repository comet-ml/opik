import React, { useMemo } from "react";
import { AlertCircle, ArrowUpRight } from "lucide-react";

import { Button } from "@/ui/button";
import { Skeleton } from "@/ui/skeleton";
import OpikLeaf from "@/icons/opik-leaf.svg?react";
import OpikLeafDark from "@/icons/opik-leaf-dark.svg?react";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import { SandboxJob, SandboxJobStatus } from "@/types/agent-sandbox";
import TraceStatsDisplay from "@/v2/pages-shared/traces/TraceStatsDisplay/TraceStatsDisplay";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

const SKELETON_BAR_WIDTHS = ["w-72", "w-64", "w-56", "w-72", "w-60", "w-52"];

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

  const { themeMode } = useTheme();
  const LeafIcon = themeMode === THEME_MODE.DARK ? OpikLeafDark : OpikLeaf;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-3 border-b px-4">
        <span className="comet-body-xs-accented text-foreground">Result</span>

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
          <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
            <LeafIcon className="h-10 w-auto" aria-hidden="true" />
            <div className="flex flex-col gap-1">
              <p className="comet-body-s font-medium text-foreground">
                No results yet
              </p>
              <p className="comet-body-xs max-w-xs text-muted-slate">
                The agent response will appear here after a run
              </p>
            </div>
          </div>
        )}

        {isRunning && (
          <div className="flex flex-1 flex-col items-center gap-6 px-6 pt-12 text-center">
            <div className="flex flex-col gap-1">
              <p className="comet-body-s font-medium text-foreground">
                Running your agent...
              </p>
              <p className="comet-body-xs text-muted-slate">
                Results will appear here when it&apos;s done.
              </p>
            </div>
            <div className="flex flex-col gap-2">
              {SKELETON_BAR_WIDTHS.map((width, i) => (
                <div key={i} className="flex items-center gap-2">
                  <Skeleton className="size-3 shrink-0 rounded-sm" />
                  <Skeleton className={`h-3 ${width}`} />
                </div>
              ))}
            </div>
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
