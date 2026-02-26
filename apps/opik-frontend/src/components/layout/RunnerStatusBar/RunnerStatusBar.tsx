import React, { useCallback, useEffect, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ChevronDown, ChevronUp, Plug, PlugZap } from "lucide-react";

import { Button } from "@/components/ui/button";
import useMyRunner, {
  getStoredRunnerId,
} from "@/api/runners/useMyRunner";
import useRunnerJobsList from "@/api/runners/useRunnerJobsList";
import { JobRow } from "@/components/pages/TracesPage/ExecutionTab/ExecutionTab";
import PairingDialog from "@/components/pages/TracesPage/ExecutionTab/PairingDialog";
import useAppStore from "@/store/AppStore";
import { RunnerJob } from "@/types/runners";

export const COLLAPSED_HEIGHT = 28;
const MIN_HEIGHT = 120;
const MAX_HEIGHT_VH = 0.8;

type RunnerStatusBarProps = {
  expanded: boolean;
  onExpandedChange: (expanded: boolean) => void;
  panelHeight: number;
  onPanelHeightChange: (height: number) => void;
};

const RunnerStatusBar: React.FunctionComponent<RunnerStatusBarProps> = ({
  expanded,
  onExpandedChange,
  panelHeight,
  onPanelHeightChange,
}) => {
  const [pairingOpen, setPairingOpen] = useState(false);
  const isDragging = useRef(false);
  const dragStartY = useRef(0);
  const dragStartHeight = useRef(0);

  const { data: runner } = useMyRunner({
    refetchInterval: 5000,
  });

  const runnerId = getStoredRunnerId();
  const isConnected = runner?.status === "connected";
  const agentCount = runner?.agents?.length ?? 0;

  const workspaceName = useAppStore((s) => s.activeWorkspaceName);

  const { data: jobs = [] } = useRunnerJobsList(
    { runnerId: runnerId ?? "" },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 2000,
      enabled: !!runnerId && isConnected,
    },
  );

  const handleToggle = () => {
    onExpandedChange(!expanded);
  };

  const handleMouseDown = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      isDragging.current = true;
      dragStartY.current = e.clientY;
      dragStartHeight.current = panelHeight;
    },
    [panelHeight],
  );

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!isDragging.current) return;
      const delta = dragStartY.current - e.clientY;
      const maxHeight = window.innerHeight * MAX_HEIGHT_VH;
      const newHeight = Math.min(
        maxHeight,
        Math.max(MIN_HEIGHT, dragStartHeight.current + delta),
      );
      onPanelHeightChange(newHeight);
    };

    const handleMouseUp = () => {
      isDragging.current = false;
    };

    window.addEventListener("mousemove", handleMouseMove);
    window.addEventListener("mouseup", handleMouseUp);
    return () => {
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("mouseup", handleMouseUp);
    };
  }, [onPanelHeightChange]);

  const renderExpandedContent = () => {
    if (!isConnected) {
      return (
        <div className="flex flex-col items-center justify-center py-8 text-center">
          <div className="mb-3 text-sm text-muted-slate">
            Connect your machine to execute agents.
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPairingOpen(true)}
          >
            Connect
          </Button>
        </div>
      );
    }

    const sortedJobs = [...jobs].sort((a, b) => {
      const ta = a.created_at ? new Date(a.created_at).getTime() : 0;
      const tb = b.created_at ? new Date(b.created_at).getTime() : 0;
      return tb - ta;
    });

    return (
      <div className="flex h-full flex-col p-4">
        {sortedJobs.length === 0 ? (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-slate">
            No runs yet. Use the Replay button on a trace to trigger execution.
          </div>
        ) : (
          <div className="min-h-0 flex-1 overflow-auto rounded-md border">
            <table className="w-full text-sm">
              <thead className="sticky top-0 z-10 bg-muted/50">
                <tr className="border-b">
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
                {sortedJobs.map((job: RunnerJob) => (
                  <JobRow
                    key={job.id}
                    job={job}
                    workspaceName={workspaceName}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    );
  };

  return (
    <>
      <div
        className="comet-content-inset absolute bottom-0 right-0 z-50 flex flex-col border-t bg-background"
        style={{ height: expanded ? `${panelHeight}px` : `${COLLAPSED_HEIGHT}px` }}
      >
        {expanded && (
          <div
            className="flex h-1.5 cursor-ns-resize items-center justify-center hover:bg-muted/50"
            onMouseDown={handleMouseDown}
          >
            <div className="h-0.5 w-8 rounded-full bg-muted-slate/40" />
          </div>
        )}
        <button
          onClick={handleToggle}
          className="flex h-7 shrink-0 items-center gap-2 px-4 text-xs transition-colors hover:bg-muted/50"
        >
          {isConnected ? (
            <>
              <span className="size-2 rounded-full bg-green-500" />
              <PlugZap className="size-3 text-green-600" />
              <span className="text-foreground">
                Connected — {runner.name}
              </span>
              {agentCount > 0 && (
                <span className="text-muted-slate">
                  · {agentCount} agent{agentCount !== 1 ? "s" : ""}
                </span>
              )}
            </>
          ) : (
            <>
              <span className="size-2 rounded-full bg-muted-slate/40" />
              <Plug className="size-3 text-muted-slate" />
              <span className="text-muted-slate">
                {runnerId ? "Runner disconnected" : "No runner connected"}
              </span>
            </>
          )}
          <span className="ml-auto">
            {expanded ? (
              <ChevronDown className="size-3 text-muted-slate" />
            ) : (
              <ChevronUp className="size-3 text-muted-slate" />
            )}
          </span>
        </button>
        {expanded && (
          <div className="min-h-0 flex-1 overflow-auto">
            {renderExpandedContent()}
          </div>
        )}
      </div>

      <PairingDialog open={pairingOpen} setOpen={setPairingOpen} />
    </>
  );
};

export default RunnerStatusBar;
