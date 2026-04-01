import React, { useMemo } from "react";
import { AlertCircle, FileOutput, Text } from "lucide-react";

import Loader from "@/shared/Loader/Loader";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import { SandboxJob, SandboxJobStatus } from "@/types/agent-sandbox";

type AgentRunnerResultProps = {
  job: SandboxJob | null;
};

const AgentRunnerResult: React.FC<AgentRunnerResultProps> = ({ job }) => {
  const resultData = useMemo((): object | undefined => {
    if (job?.result === undefined) return undefined;
    if (typeof job.result === "object" && job.result !== null) {
      return job.result as object;
    }
    return { output: job.result };
  }, [job?.result]);

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

      {job?.status === SandboxJobStatus.COMPLETED &&
        resultData !== undefined && (
          <div className="py-2">
            <SyntaxHighlighter
              data={resultData}
              prettifyConfig={{ fieldType: "output" }}
            />
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
