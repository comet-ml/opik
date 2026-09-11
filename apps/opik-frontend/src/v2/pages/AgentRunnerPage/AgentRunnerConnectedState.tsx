import React, { useCallback, useRef } from "react";

import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import { LocalRunner } from "@/types/agent-sandbox";
import AgentRunnerInputForm from "./AgentRunnerInputForm";
import AgentRunnerLoading from "./AgentRunnerLoading";
import AgentRunnerPromptsList, {
  AgentRunnerPromptsListHandle,
} from "./AgentRunnerPromptsList";

type AgentRunnerConnectedStateProps = {
  projectId: string;
  runner: LocalRunner;
  onRun: (
    inputs: Record<string, unknown>,
    promptMasks: Record<string, string>,
  ) => void;
  isRunning: boolean;
  resetKey: number;
  onValidityChange?: (hasAllRequired: boolean) => void;
};

const AgentRunnerConnectedState: React.FC<AgentRunnerConnectedStateProps> = ({
  projectId,
  runner,
  onRun,
  isRunning,
  resetKey,
  onValidityChange,
}) => {
  const agent = runner.agents?.[0];
  const inputFields = agent?.params ?? [];
  const promptsRef = useRef<AgentRunnerPromptsListHandle>(null);

  const handleSubmit = useCallback(
    async (inputs: Record<string, unknown>) => {
      try {
        const masks = (await promptsRef.current?.prepareMasks()) ?? {};
        onRun(inputs, masks);
      } catch {
        // Per-card mutations surface their own toasts on failure.
      }
    },
    [onRun],
  );

  return (
    <ResizablePanelGroup
      direction="horizontal"
      autoSaveId="agent-runner-input-prompts"
      className="h-full min-h-0"
    >
      <ResizablePanel id="agent-runner-input" defaultSize={50} minSize={25}>
        <div className="flex h-full min-h-0 flex-col">
          <div className="comet-body-xs-accented flex h-10 shrink-0 items-center border-b bg-soft-background px-4 text-foreground">
            Test input
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-4">
            {agent ? (
              <AgentRunnerInputForm
                key={resetKey}
                fields={inputFields}
                onSubmit={handleSubmit}
                isRunning={isRunning}
                onValidityChange={onValidityChange}
              />
            ) : (
              <AgentRunnerLoading runnerId={runner.id} />
            )}
          </div>
        </div>
      </ResizablePanel>

      <ResizableHandle />

      <ResizablePanel id="agent-runner-prompts" defaultSize={50} minSize={25}>
        <div className="flex h-full min-h-0 flex-col">
          <div className="comet-body-xs-accented flex h-10 shrink-0 items-center border-b bg-soft-background px-4 text-foreground">
            Prompts
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-4">
            <AgentRunnerPromptsList ref={promptsRef} projectId={projectId} />
          </div>
        </div>
      </ResizablePanel>
    </ResizablePanelGroup>
  );
};

export default AgentRunnerConnectedState;
