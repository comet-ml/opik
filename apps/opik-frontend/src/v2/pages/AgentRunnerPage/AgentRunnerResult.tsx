import React from "react";
import { AlertCircle, FileOutput, Text } from "lucide-react";

import Loader from "@/shared/Loader/Loader";
import { SandboxJob, SandboxJobStatus } from "@/types/agent-sandbox";

type AgentRunnerResultProps = {
  job: SandboxJob | null;
};

const AgentRunnerResult: React.FC<AgentRunnerResultProps> = ({ job }) => {
  return (
    <div>
      <div className="mb-3 flex items-center gap-2">
        <FileOutput className="size-3.5 text-muted-slate" />
        <span className="comet-body-s-accented">Result</span>
      </div>

      {!job && (
        <div className="flex flex-col items-center py-8 text-muted-slate">
          <Text className="mb-2 size-5" />
          <p className="comet-body-s font-medium">No results yet</p>
          <p className="comet-body-xs mt-1 text-center">
            The agent response will
            <br />
            appear here after a run.
          </p>
        </div>
      )}

      {job?.status === SandboxJobStatus.PENDING && (
        <div className="flex items-center gap-2 py-4 text-muted-slate">
          <Loader className="size-3.5" />
          <span className="comet-body-s">Job queued...</span>
        </div>
      )}

      {job?.status === SandboxJobStatus.RUNNING && (
        <div className="flex items-center gap-2 py-4 text-muted-slate">
          <Loader className="size-3.5" />
          <span className="comet-body-s">Agent is running...</span>
        </div>
      )}

      {job?.status === SandboxJobStatus.COMPLETED && (
        <div className="py-2">
          <pre className="comet-body-s whitespace-pre-wrap rounded-md bg-slate-50 p-4">
            {typeof job.result === "string"
              ? job.result
              : JSON.stringify(job.result, null, 2)}
          </pre>
        </div>
      )}

      {job &&
        [SandboxJobStatus.FAILED, SandboxJobStatus.CANCELLED].includes(
          job.status,
        ) && (
          <div className="flex items-start gap-2 rounded-md border border-destructive/20 bg-destructive/5 p-4">
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
  );
};

export default AgentRunnerResult;
