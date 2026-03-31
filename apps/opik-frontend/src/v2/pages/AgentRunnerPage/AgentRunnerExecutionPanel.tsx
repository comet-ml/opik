import React from "react";
import { ListTree } from "lucide-react";

type AgentRunnerExecutionPanelProps = {
  jobId: string | null;
};

const AgentRunnerExecutionPanel: React.FC<AgentRunnerExecutionPanelProps> = ({
  jobId,
}) => {
  return (
    <div className="flex h-full flex-col">
      <span className="comet-body-s-accented mb-4">Trajectory</span>

      {!jobId && (
        <div className="flex flex-1 flex-col items-center justify-center text-muted-slate">
          <ListTree className="mb-2 size-5" />
          <p className="comet-body-s font-medium">No run trace yet</p>
          <p className="comet-body-xs mt-1 text-center">
            Run your agent to see how it
            <br />
            executes step by step
          </p>
        </div>
      )}

      {jobId && (
        <p className="comet-body-xs text-muted-slate">
          Execution trace will appear here once the agent completes.
        </p>
      )}
    </div>
  );
};

export default AgentRunnerExecutionPanel;
