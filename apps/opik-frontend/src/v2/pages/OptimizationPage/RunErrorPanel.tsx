import React, { useMemo, useState } from "react";
import { TriangleAlert } from "lucide-react";

import { Button } from "@/ui/button";
import { Optimization } from "@/types/optimizations";
import useOptimizationStudioLogs from "@/api/optimizations/useOptimizationStudioLogs";
import { convertTerminalOutputToHtml } from "@/lib/terminalOutput";
import OptimizationLogsFullscreenDialog from "@/v2/pages-shared/optimizations/OptimizationLogs/OptimizationLogsFullscreenDialog";
import { extractErrorFromLogs } from "./runError";

type RunErrorPanelProps = {
  optimization: Optimization;
};

/**
 * Shown when a run ends in error: surfaces the failure reason (preferring the
 * structured `error_info.message` persisted on the optimization, falling back
 * to scraping the studio logs) plus a link to the full logs — instead of the
 * bare red status badge.
 */
const RunErrorPanel: React.FC<RunErrorPanelProps> = ({ optimization }) => {
  const [open, setOpen] = useState(false);
  const { data, dataUpdatedAt, isError, refetch } = useOptimizationStudioLogs(
    { optimizationId: optimization.id },
    { enabled: Boolean(optimization.id), retry: false },
  );

  const logContent = data?.content ?? "";
  const logsFailedToLoad = isError && !logContent;
  const errorMessage = useMemo(
    () => optimization.error_info?.message?.trim() || extractErrorFromLogs(logContent),
    [optimization.error_info?.message, logContent],
  );
  const logHtml = useMemo(
    () => convertTerminalOutputToHtml(logContent),
    [logContent],
  );

  return (
    <>
      <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4">
        <div className="mb-1 flex items-center gap-2">
          <TriangleAlert className="size-4 shrink-0 text-destructive" />
          <h3 className="comet-body-s-accented text-destructive">
            Optimization failed
          </h3>
        </div>
        <p className="comet-body-xs whitespace-pre-wrap break-words text-foreground">
          {errorMessage ??
            (logsFailedToLoad
              ? "The run ended with an error, but the logs could not be loaded."
              : "The run ended with an error. Open the logs for details.")}
          {logsFailedToLoad && (
            <>
              {" "}
              {/* Inline, text-sized underlined link — not a block button on its
                  own line (design QA round 2). */}
              <Button
                variant="link"
                className="comet-body-xs inline h-auto p-0 align-baseline underline"
                onClick={() => refetch()}
              >
                Retry
              </Button>
            </>
          )}
        </p>
        {logContent && (
          <Button
            variant="outline"
            size="sm"
            className="mt-3"
            onClick={() => setOpen(true)}
          >
            View logs
          </Button>
        )}
      </div>
      <OptimizationLogsFullscreenDialog
        open={open}
        onOpenChange={setOpen}
        onClose={() => {}}
        logContent={logContent}
        logHtml={logHtml}
        isInProgress={false}
        lastUpdatedAt={
          dataUpdatedAt ? new Date(dataUpdatedAt).toISOString() : null
        }
        hasNewLogs={false}
        initialScrollRatio={1}
      />
    </>
  );
};

export default RunErrorPanel;
