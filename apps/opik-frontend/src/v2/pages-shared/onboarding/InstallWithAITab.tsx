import React from "react";
import { useUserApiKey, useActiveWorkspaceName } from "@/store/AppStore";
import { buildDocsUrl } from "@/lib/utils";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";
import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import { INSTALL_OPIK_SKILLS_COMMAND } from "@/constants/shared";
import AgentCopyButtons from "@/v2/pages-shared/onboarding/AgentCopyButtons";

interface InstallWithAITabProps {
  traceReceived: boolean;
  agentName?: string;
  showTraceStep?: boolean;
}

const InstallWithAITab: React.FC<InstallWithAITabProps> = ({
  traceReceived,
  agentName = "",
  showTraceStep = true,
}) => {
  const apiKey = useUserApiKey();
  const workspaceName = useActiveWorkspaceName();

  const projectPart = agentName ? `, project name "${agentName}"` : "";
  const prompt = `Instrument my agent with Opik using the /instrument command. Make sure you use workspace "${workspaceName}"${projectPart} and API key "${
    apiKey ?? "<YOUR_API_KEY>"
  }".`;

  return (
    <div className="flex flex-col gap-4 px-1">
      <div className="flex flex-wrap items-center gap-2">
        <span className="comet-body-s-accented">Works with</span>
        <div className="flex items-center gap-1.5 rounded border px-2 py-1">
          <img src={claudeCodeLogo} alt="Claude Code" className="size-4" />
          <span className="comet-body-xs-accented">Claude Code</span>
        </div>
        <div className="flex items-center gap-1.5 rounded border px-2 py-1">
          <img src={codexLogo} alt="Codex" className="size-4" />
          <span className="comet-body-xs-accented">Codex</span>
        </div>
        <div className="flex items-center gap-1.5 rounded border px-2 py-1">
          <img src={cursorLogo} alt="Cursor" className="size-4" />
          <span className="comet-body-xs-accented">Cursor</span>
        </div>
        <span className="comet-body-xs text-muted-slate">+34 more</span>
      </div>

      <AgentCopyButtons agentName={agentName} />

      <div className="flex flex-col">
        <TimelineStep number={1}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">Add the Opik skill</h4>
            <CodeSnippet title="Terminal" code={INSTALL_OPIK_SKILLS_COMMAND} />
          </div>
        </TimelineStep>

        <TimelineStep number={2} isLast={!showTraceStep}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">
              Open your coding agent and paste this prompt
            </h4>
            <CodeSnippet title="Prompt" code={prompt} />
          </div>
        </TimelineStep>

        {showTraceStep && (
          <TimelineStep isLast completed={traceReceived}>
            <div className="flex flex-col gap-1">
              <h4 className="comet-body-s-accented text-primary">
                {traceReceived
                  ? "First trace received! You're all set."
                  : "Waiting for first trace…"}
              </h4>
              <p className="comet-body-xs text-muted-slate">
                {traceReceived ? (
                  "Traces are flowing. You can now debug, evaluate, and optimize."
                ) : (
                  <>
                    Connect your agent to Opik for observability, evaluation and
                    optimization.{" "}
                    <a
                      href={buildDocsUrl("/faq", "#troubleshooting")}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="underline hover:text-foreground"
                    >
                      Why isn&apos;t my trace showing?
                    </a>
                  </>
                )}
              </p>
            </div>
          </TimelineStep>
        )}
      </div>
    </div>
  );
};

export default InstallWithAITab;
