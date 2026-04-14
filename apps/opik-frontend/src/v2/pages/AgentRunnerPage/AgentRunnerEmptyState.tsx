import React from "react";
import { ExternalLink } from "lucide-react";

import { buildDocsUrl } from "@/lib/utils";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";
import AgentSandboxFlowDiagram from "./AgentSandboxFlowDiagram";
import ProjectIcon from "@/shared/ProjectIcon/ProjectIcon";

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
              <CodeSnippet title="Terminal" code={command} />
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
