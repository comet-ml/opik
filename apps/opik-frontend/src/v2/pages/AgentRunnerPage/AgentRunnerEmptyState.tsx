import React from "react";
import { ExternalLink, LoaderCircle } from "lucide-react";

import CopyButton from "@/shared/CopyButton/CopyButton";
import { buildDocsUrl } from "@/lib/utils";
import AgentSandboxFlowDiagram from "./AgentSandboxFlowDiagram";
import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";

const TimelineStep: React.FC<{
  number?: number;
  isLast?: boolean;
  children: React.ReactNode;
}> = ({ number, isLast, children }) => (
  <div className="flex gap-3">
    <div className="flex flex-col items-center">
      {number != null ? (
        <div className="flex size-4 shrink-0 items-center justify-center rounded-full border border-[var(--timeline-connector)] text-[8px] font-semibold text-[var(--timeline-connector)]">
          {number}
        </div>
      ) : (
        <div className="relative flex size-4 shrink-0 items-center justify-center">
          <div className="absolute inset-0 animate-ping rounded-full bg-primary/20" />
          <LoaderCircle className="relative size-3.5 animate-spin text-primary" />
        </div>
      )}
      {!isLast && (
        <div className="w-px flex-1 bg-[var(--timeline-connector)] opacity-50" />
      )}
    </div>
    <div className="flex-1 pb-6">{children}</div>
  </div>
);

const CodeBlockWithHeader: React.FC<{
  title: string;
  code: string;
}> = ({ title, code }) => (
  <div className="overflow-hidden rounded-md border bg-primary-foreground">
    <div className="flex items-center justify-between border-b px-2.5 py-1">
      <span className="comet-body-xs text-muted-slate">{title}</span>
      <CopyButton
        text={code}
        message="Command copied to clipboard"
        tooltipText="Copy"
        size="icon-3xs"
      />
    </div>
    <pre className="whitespace-pre-wrap break-words px-2.5 py-2 font-code text-[13px] leading-snug">
      {code}
    </pre>
  </div>
);

const AgentRunnerEmptyState: React.FC = () => {
  const command = `opik endpoint --project <project_name> -- <your app start command>`;

  return (
    <div className="flex flex-1 justify-center gap-16 px-10 pt-[15.69rem]">
      <div className="w-full max-w-lg">
        <div className="mb-1 flex items-center gap-2">
          <ProjectIcon index={0} size="lg" />
          <h2 className="comet-title-m">Connect your agent</h2>
        </div>
        <p className="comet-body-s mb-8 text-muted-slate">
          Link your agent to Opik to start using the Agent sandbox and improve
          performance.
        </p>

        <div className="flex flex-col">
          <TimelineStep number={1}>
            <div className="flex flex-col gap-2.5">
              <h4 className="comet-body-s-accented">
                Run the connection command
              </h4>
              <p className="comet-body-xs text-muted-slate">
                Install and run the Opik connector in your terminal (works with
                Claude Code, Codex, Cursor, Windsurf, and more):
              </p>
              <CodeBlockWithHeader title="Terminal" code={command} />
            </div>
          </TimelineStep>

          <TimelineStep isLast>
            <div className="flex flex-col gap-1">
              <h4 className="comet-body-s-accented text-primary">
                Waiting for connection
              </h4>
              <p className="comet-body-xs text-muted-slate">
                We&apos;ll automatically detect your agent once it&apos;s
                connected.
              </p>
              <p className="comet-body-xs text-muted-slate">
                Trouble connecting?{" "}
                <a
                  href={buildDocsUrl("/faq#troubleshooting")}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1 underline hover:text-foreground"
                >
                  Check troubleshooting
                  <ExternalLink className="size-3" />
                </a>
              </p>
            </div>
          </TimelineStep>
        </div>
      </div>

      {/* Flow diagram */}
      <div className="hidden shrink-0 pt-4 xl:block">
        <AgentSandboxFlowDiagram />
      </div>
    </div>
  );
};

export default AgentRunnerEmptyState;
