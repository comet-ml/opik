import React, { useEffect, useState } from "react";
import { AlertCircle, Loader2 } from "lucide-react";

const NO_AGENTS_GRACE_MS = 20_000;

type AgentRunnerLoadingProps = {
  runnerId: string;
};

const AgentRunnerLoading: React.FC<AgentRunnerLoadingProps> = ({
  runnerId,
}) => {
  const [isGraceElapsed, setIsGraceElapsed] = useState(false);

  useEffect(() => {
    setIsGraceElapsed(false);
    const timer = window.setTimeout(
      () => setIsGraceElapsed(true),
      NO_AGENTS_GRACE_MS,
    );
    return () => window.clearTimeout(timer);
  }, [runnerId]);

  if (isGraceElapsed) {
    return (
      <div className="flex flex-col items-center gap-1 py-8 text-muted-slate">
        <AlertCircle className="mb-2 size-5 text-destructive" />
        <p className="comet-body-s font-medium text-foreground">
          No agents registered
        </p>
        <div className="comet-body-xs mt-1 max-w-sm text-center">
          <p>
            The runner is connected but has not registered any agents. Common
            causes:
          </p>
          <ul className="mt-1 inline-block list-inside list-disc text-left">
            <li>
              Missing <code>@opik.track(entrypoint=True)</code> decorator
            </li>
            <li>Process exited or crashed before registration</li>
            <li>Script didn&apos;t import the entrypoint module</li>
          </ul>
          <p className="mt-2">
            Check your <code>opik endpoint</code> terminal for details.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center gap-2 py-8 text-muted-slate">
      <Loader2 className="size-5 animate-spin text-primary" />
      <p className="comet-body-s">Loading agent...</p>
    </div>
  );
};

export default AgentRunnerLoading;
