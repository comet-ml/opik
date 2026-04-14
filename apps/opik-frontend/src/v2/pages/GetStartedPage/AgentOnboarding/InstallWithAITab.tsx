import React from "react";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import { useUserApiKey, useActiveWorkspaceName } from "@/store/AppStore";
import { buildDocsUrl, maskAPIKey } from "@/lib/utils";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";
import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";

const INSTALL_COMMAND = "npx skills add comet-ml/opik-skills -g --all";

interface InstallWithAITabProps {
  traceReceived: boolean;
}

const InstallWithAITab: React.FC<InstallWithAITabProps> = ({
  traceReceived,
}) => {
  const { agentName } = useAgentOnboarding();
  const apiKey = useUserApiKey();
  const workspaceName = useActiveWorkspaceName();

  const buildPrompt = (shouldMaskAPIKey: boolean) =>
    `Instrument my agent with Opik using the /instrument command. Make sure you use workspace "${workspaceName}", project name "${agentName}"${
      apiKey
        ? ` and API key "${shouldMaskAPIKey ? maskAPIKey(apiKey) : apiKey}"`
        : ""
    }. Once you are ready with the instrumentation of your agent, run it with a couple of interactions so that we make sure that the observability is correctly instrumented and the right traces are flowing to the Opik dashboard.`;

  const promptText = buildPrompt(false);
  const displayPromptText = buildPrompt(true);

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

      <div className="flex flex-col">
        <TimelineStep number={1}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">Add the Opik skill</h4>
            <p className="comet-body-xs text-muted-slate">
              Install the Opik skill so it&apos;s available in Claude Code,
              Codex, Cursor, Windsurf, and other AI editors.
            </p>
            <CodeSnippet title="Terminal" code={INSTALL_COMMAND} />
          </div>
        </TimelineStep>

        <TimelineStep number={2}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">
              Open your coding agent and paste this prompt
            </h4>
            <p className="comet-body-xs text-muted-slate">
              Navigate to the repo you want to instrument and paste this prompt.
              It will instrument your agent with Opik tracing automatically.
            </p>
            <CodeSnippet
              title="Prompt"
              code={displayPromptText}
              copyText={promptText}
            />
          </div>
        </TimelineStep>

        <TimelineStep isLast completed={traceReceived}>
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-s-accented text-primary">
              {traceReceived
                ? "First trace received! You're all set."
                : "Waiting for first trace\u2026"}
            </h4>
            <p className="comet-body-xs text-muted-slate">
              {traceReceived ? (
                "Traces are flowing. You can now debug, evaluate, and optimize."
              ) : (
                <>
                  Connect your agent to Opik for observability, evaluation and
                  optimization.{" "}
                  <a
                    href={buildDocsUrl("/faq#troubleshooting")}
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
      </div>
    </div>
  );
};

export default InstallWithAITab;
