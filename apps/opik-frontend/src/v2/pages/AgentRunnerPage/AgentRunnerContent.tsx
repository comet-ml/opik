import React, { useCallback, useEffect, useState } from "react";
import { Pause, Play, RotateCcw } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";

import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import useSandboxPairCode from "@/api/agent-sandbox/useSandboxPairCode";
import useSandboxConnectionStatus from "@/api/agent-sandbox/useSandboxConnectionStatus";
import { AGENT_SANDBOX_KEY } from "@/api/api";
import useSandboxCreateJobMutation from "@/api/agent-sandbox/useSandboxCreateJobMutation";
import useSandboxJobStatus from "@/api/agent-sandbox/useSandboxJobStatus";
import {
  SandboxConnectionStatus,
  SandboxJobStatus,
} from "@/types/agent-sandbox";
import useTraceById from "@/api/traces/useTraceById";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import AgentRunnerEmptyState from "./AgentRunnerEmptyState";
import AgentRunnerConnectedState from "./AgentRunnerConnectedState";
import AgentRunnerResult from "./AgentRunnerResult";

type AgentRunnerContentProps = {
  projectId: string;
};

const AgentRunnerContent: React.FC<AgentRunnerContentProps> = ({
  projectId,
}) => {
  const [activeJobId, setActiveJobId] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const [traceOpen, setTraceOpen] = useState(false);
  const [tracePanelSpanId, setTracePanelSpanId] = useState<
    string | null | undefined
  >("");

  const {
    data: pairCodeData,
    isPending: isPairCodePending,
    refetch: refetchPairCode,
  } = useSandboxPairCode({ projectId });

  const pairCode = pairCodeData?.pair_code ?? "";

  const { data: runnerData } = useSandboxConnectionStatus({ projectId });

  const isConnected = runnerData?.status === SandboxConnectionStatus.CONNECTED;

  const createJobMutation = useSandboxCreateJobMutation();

  const { data: jobData } = useSandboxJobStatus({
    jobId: activeJobId ?? "",
  });

  // On connect: drop the consumed pair code so a disconnect fetches a fresh one.
  // On disconnect: clear job state so results/errors from the previous session don't persist.
  useEffect(() => {
    if (isConnected) {
      queryClient.removeQueries({
        queryKey: [AGENT_SANDBOX_KEY, "pair-code", { projectId }],
      });
    } else {
      setActiveJobId(null);
    }
  }, [isConnected, queryClient, projectId]);

  const traceId = jobData?.trace_id ?? "";
  const isTraceOpen = traceOpen && Boolean(traceId);

  const isJobRunning =
    jobData?.status === SandboxJobStatus.RUNNING ||
    jobData?.status === SandboxJobStatus.PENDING;

  const { data: traceData } = useTraceById(
    { traceId, stripAttachments: true },
    {
      enabled: Boolean(traceId),
      refetchInterval: isJobRunning ? 1000 : false,
    },
  );

  const agentName = runnerData?.agents?.[0]?.name ?? "";

  const handleRun = (inputs: Record<string, unknown>, maskId?: string) => {
    if (!agentName) {
      return;
    }
    setActiveJobId(null);
    setTraceOpen(false);
    createJobMutation.mutate(
      {
        agent_name: agentName,
        project_id: projectId,
        inputs,
        mask_id: maskId,
      },
      {
        onSuccess: (data) => {
          setActiveJobId(data.id);
        },
      },
    );
  };

  const handleReset = () => {
    setActiveJobId(null);
    setTraceOpen(false);
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

  if (isPairCodePending) {
    return <Loader />;
  }

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
                <Button variant="outline" size="2xs" onClick={handleReset}>
                  <Pause className="mr-1 size-3.5" />
                  Stop run
                </Button>
              ) : (
                <Button
                  size="2xs"
                  onClick={handleSubmitForm}
                  disabled={createJobMutation.isPending || !agentName}
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

      {isConnected ? (
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
              runner={runnerData!}
              onRun={handleRun}
              isRunning={createJobMutation.isPending}
            />
          </ResizablePanel>

          <ResizableHandle />

          <ResizablePanel id="agent-result" defaultSize={50} minSize={15}>
            <AgentRunnerResult
              job={jobData ?? null}
              onViewTrace={handleViewTrace}
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
          <AgentRunnerEmptyState
            pairCode={pairCode}
            expiresInSeconds={pairCodeData?.expires_in_seconds}
            createdAt={pairCodeData?.created_at}
            onRefreshPairCode={() => refetchPairCode()}
          />
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
        />
      )}
    </div>
  );
};

export default AgentRunnerContent;
