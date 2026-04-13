import React, { useCallback, useEffect, useState } from "react";
import { Pause, Play, RotateCcw } from "lucide-react";

import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import useSandboxCreateJobMutation from "@/api/agent-sandbox/useSandboxCreateJobMutation";
import useSandboxJobStatus from "@/api/agent-sandbox/useSandboxJobStatus";
import {
  RunnerConnectionStatus,
  SandboxJobStatus,
} from "@/types/agent-sandbox";
import useTraceById from "@/api/traces/useTraceById";
import usePairingState from "@/hooks/usePairingState";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import AgentRunnerEmptyState from "./AgentRunnerEmptyState";
import AgentRunnerConnectedState from "./AgentRunnerConnectedState";
import AgentRunnerResult from "./AgentRunnerResult";

const TRACE_POLL_INTERVAL = 1000;

type AgentRunnerContentProps = {
  projectId: string;
};

const AgentRunnerContent: React.FC<AgentRunnerContentProps> = ({
  projectId,
}) => {
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const [traceOpen, setTraceOpen] = useState(false);
  const [tracePanelSpanId, setTracePanelSpanId] = useState<
    string | null | undefined
  >("");

  const pairing = usePairingState(projectId);

  const isConnected = pairing.status === RunnerConnectionStatus.CONNECTED;
  const agentName = pairing.runner?.agents?.[0]?.name ?? "";
  const isReady = isConnected && Boolean(agentName);

  const createJobMutation = useSandboxCreateJobMutation();

  const { data: jobData } = useSandboxJobStatus({
    jobId: activeJobId ?? "",
  });

  // Clear job state on disconnect so results/errors from the previous session don't persist.
  useEffect(() => {
    if (!isConnected) {
      setActiveJobId(null);
    }
  }, [isConnected]);

  const traceId = jobData?.trace_id ?? "";
  const isTraceOpen = traceOpen && Boolean(traceId);

  const isJobRunning =
    jobData?.status === SandboxJobStatus.RUNNING ||
    jobData?.status === SandboxJobStatus.PENDING;

  const { data: traceData } = useTraceById(
    { traceId, stripAttachments: true },
    {
      enabled: Boolean(traceId),
      refetchInterval: isJobRunning ? TRACE_POLL_INTERVAL : false,
    },
  );

  const handleRun = (
    inputs: Record<string, unknown>,
    blueprintName?: string,
    maskId?: string,
  ) => {
    if (!agentName) {
      return;
    }
    handleStop();
    createJobMutation.mutate(
      {
        agent_name: agentName,
        project_id: projectId,
        inputs,
        mask_id: maskId,
        blueprint_name: blueprintName,
      },
      {
        onSuccess: (data) => {
          setActiveJobId(data.id);
        },
      },
    );
  };

  const [resetKey, setResetKey] = useState(0);

  const handleStop = () => {
    setActiveJobId(null);
    setTraceOpen(false);
  };

  const handleReset = () => {
    handleStop();
    setResetKey((k) => k + 1);
  };

  const handleSubmitForm = useCallback(() => {
    if (createJobMutation.isPending || isJobRunning) return;
    const form = document.getElementById("agent-runner-form");
    if (form) {
      form.dispatchEvent(
        new Event("submit", { cancelable: true, bubbles: true }),
      );
    }
  }, [createJobMutation.isPending, isJobRunning]);

  const handleViewTrace = useCallback(() => {
    if (jobData?.trace_id) {
      setTracePanelSpanId("");
      setTraceOpen(true);
    }
  }, [jobData?.trace_id]);

  useEffect(() => {
    if (!isConnected) return;
    const handler = (e: KeyboardEvent) => {
      if (e.shiftKey && e.key === "Enter") {
        e.preventDefault();
        handleSubmitForm();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [isConnected, handleSubmitForm]);

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 border-b bg-gray-100 px-4 py-3">
        <h1 className="comet-title-xs">Agent sandbox</h1>

        {isConnected ? (
          <TooltipWrapper content="Your agent is connected to Opik">
            <span className="comet-body-xs flex items-center gap-1.5 text-emerald-600">
              <span className="size-1.5 rounded-full bg-emerald-500" />
              Connected
            </span>
          </TooltipWrapper>
        ) : (
          <span className="comet-body-xs flex items-center gap-1.5 text-rose-500">
            <span className="size-1.5 rounded-full bg-rose-500" />
            Disconnected
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          {isConnected && (
            <>
              {isJobRunning ? (
                <Button variant="outline" size="2xs" onClick={handleStop}>
                  <Pause className="mr-1 size-3.5" />
                  Stop run
                </Button>
              ) : (
                <Button
                  size="2xs"
                  onClick={handleSubmitForm}
                  disabled={createJobMutation.isPending || !isReady}
                >
                  <Play className="mr-1 size-3.5" />
                  Run
                  <HotkeyDisplay hotkey="⇧" size="2xs" className="ml-1.5" />
                  <HotkeyDisplay hotkey="⏎" size="2xs" className="ml-1" />
                </Button>
              )}
              <Button variant="ghost" size="2xs" onClick={handleReset}>
                <RotateCcw className="mr-1 size-3.5" />
                Reset
              </Button>
            </>
          )}
        </div>
      </div>

      {isConnected && pairing.runner ? (
        <ResizablePanelGroup
          direction="vertical"
          autoSaveId="agent-sandbox-layout"
          className="min-h-0 flex-1"
        >
          <ResizablePanel
            id="agent-input"
            defaultSize={50}
            minSize={20}
            className="overflow-y-auto"
          >
            <AgentRunnerConnectedState
              projectId={projectId}
              runner={pairing.runner}
              onRun={handleRun}
              isRunning={createJobMutation.isPending}
              resetKey={resetKey}
            />
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="agent-result" defaultSize={50} minSize={15}>
            <AgentRunnerResult
              job={jobData ?? null}
              onViewTrace={handleViewTrace}
              hasTraceData={Boolean(traceData)}
              duration={traceData?.duration}
              startTime={traceData?.start_time}
              endTime={traceData?.end_time}
              totalTokens={traceData?.usage?.total_tokens}
              totalEstimatedCost={traceData?.total_estimated_cost}
            />
          </ResizablePanel>
        </ResizablePanelGroup>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto">
          <AgentRunnerEmptyState />
        </div>
      )}

      {/* Trace side panel - only mount when open to avoid stale cache */}
      {isTraceOpen && (
        <TraceDetailsPanel
          projectId={projectId}
          traceId={traceId}
          spanId={String(tracePanelSpanId ?? "")}
          setSpanId={setTracePanelSpanId}
          open
          onClose={() => setTraceOpen(false)}
          refetchInterval={TRACE_POLL_INTERVAL}
        />
      )}
    </div>
  );
};

export default AgentRunnerContent;
