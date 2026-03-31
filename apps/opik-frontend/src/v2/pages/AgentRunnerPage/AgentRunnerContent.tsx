import React, { useCallback, useEffect, useState } from "react";
import { Play, RotateCcw } from "lucide-react";

import { Button } from "@/ui/button";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useSandboxPairCode from "@/api/agent-sandbox/useSandboxPairCode";
import useSandboxConnectionStatus from "@/api/agent-sandbox/useSandboxConnectionStatus";
import useSandboxCreateJobMutation from "@/api/agent-sandbox/useSandboxCreateJobMutation";
import useSandboxJobStatus from "@/api/agent-sandbox/useSandboxJobStatus";
import { SandboxConnectionStatus } from "@/types/agent-sandbox";
import AgentRunnerEmptyState from "./AgentRunnerEmptyState";
import AgentRunnerConnectedState from "./AgentRunnerConnectedState";
import AgentRunnerResult from "./AgentRunnerResult";
import AgentRunnerExecutionPanel from "./AgentRunnerExecutionPanel";

type AgentRunnerContentProps = {
  projectId: string;
};

const AgentRunnerContent: React.FC<AgentRunnerContentProps> = ({
  projectId,
}) => {
  const [activeJobId, setActiveJobId] = useState<string | null>(null);

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

  const agentName = runnerData?.agents?.[0]?.name ?? "";

  const handleRun = (inputs: Record<string, unknown>, maskId?: string) => {
    if (!agentName) {
      return;
    }
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
  };

  const handleSubmitForm = useCallback(() => {
    if (createJobMutation.isPending) return;
    const form = document.getElementById("agent-runner-form");
    if (form) {
      form.dispatchEvent(
        new Event("submit", { cancelable: true, bubbles: true }),
      );
    }
  }, [createJobMutation.isPending]);

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
      {/* Header */}
      <div className="flex items-center gap-3 border-b px-5 py-2.5">
        <h1 className="comet-title-s">Agent sandbox</h1>

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
              <Button
                size="sm"
                onClick={handleSubmitForm}
                disabled={createJobMutation.isPending || !agentName}
                className="gap-2"
              >
                <span className="flex items-center gap-1.5">
                  <Play className="size-3.5" />
                  Run
                </span>
                <span className="flex items-center gap-1">
                  <kbd className="bg-white/20 inline-flex h-5 w-7 items-center justify-center rounded text-lg">
                    ⇧
                  </kbd>
                  <kbd className="bg-white/20 inline-flex h-5 w-7 items-center justify-center rounded text-lg">
                    ↩
                  </kbd>
                </span>
              </Button>
              <Button variant="outline" size="sm" onClick={handleReset}>
                <RotateCcw className="mr-1.5 size-3.5" />
                Reset
              </Button>
            </>
          )}
        </div>
      </div>

      {/* Content */}
      {isConnected ? (
        <div className="flex min-h-0 flex-1">
          {/* Left panel */}
          <div className="flex flex-1 flex-col overflow-y-auto">
            <AgentRunnerConnectedState
              projectId={projectId}
              runner={runnerData!}
              onRun={handleRun}
              isRunning={createJobMutation.isPending}
              result={<AgentRunnerResult job={jobData ?? null} />}
            />
          </div>

          {/* Right panel - Trajectory */}
          <div className="w-2/5 shrink-0 overflow-y-auto border-l p-6">
            <AgentRunnerExecutionPanel jobId={activeJobId} />
          </div>
        </div>
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
    </div>
  );
};

export default AgentRunnerContent;
