import React from "react";
import { LoaderCircle } from "lucide-react";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import { useUserApiKey } from "@/store/AppStore";
import { buildDocsUrl, maskAPIKey } from "@/lib/utils";
import CopyButton from "@/shared/CopyButton/CopyButton";
import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";

const INSTALL_COMMAND = "npx skills add comet-ml/opik-skills -g";

const TimelineStep: React.FC<{
  number?: number;
  isLast?: boolean;
  children: React.ReactNode;
}> = ({ number, isLast, children }) => (
  <div className="flex gap-3">
    <div className="flex flex-col items-center">
      {number != null ? (
        <div className="flex size-5 shrink-0 items-center justify-center rounded-full border border-border text-[10px] font-medium text-muted-slate">
          {number}
        </div>
      ) : (
        <div className="flex size-5 shrink-0 items-center justify-center">
          <LoaderCircle className="size-4 animate-spin text-primary" />
        </div>
      )}
      {!isLast && <div className="mt-1 w-px flex-1 bg-border" />}
    </div>
    <div className="flex-1 pb-6">{children}</div>
  </div>
);

const CodeBlockWithHeader: React.FC<{
  title: string;
  code: string;
  copyText?: string;
}> = ({ title, code, copyText }) => (
  <div className="overflow-hidden rounded-md border bg-primary-foreground">
    <div className="flex items-center justify-between border-b px-3 py-1.5">
      <span className="comet-body-xs text-muted-slate">{title}</span>
      <CopyButton
        text={copyText ?? code}
        message="Successfully copied"
        tooltipText="Copy"
        size="icon-xs"
      />
    </div>
    <pre className="whitespace-pre-wrap break-words p-2 font-code text-[13px] leading-snug">
      {code}
    </pre>
  </div>
);

const InstallWithAITab: React.FC = () => {
  const { agentName } = useAgentOnboarding();
  const apiKey = useUserApiKey();

  const fallbackApiKey = "opk-***-your-api-key";

  const buildPrompt = (key: string) =>
    `Instrument my agent with Opik, use project name "${agentName}" and API key "${key}".`;

  const promptText = buildPrompt(apiKey || fallbackApiKey);
  const displayPromptText = buildPrompt(
    apiKey ? maskAPIKey(apiKey) : fallbackApiKey,
  );

  return (
    <div className="flex flex-col gap-4">
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
          <div className="flex flex-col gap-1.5">
            <h4 className="comet-body-s-accented">Add the Opik skill</h4>
            <p className="comet-body-xs text-muted-slate">
              Install the Opik skill so it&apos;s available in Claude Code,
              Codex, Cursor, Windsurf, and other AI editors.
            </p>
            <CodeBlockWithHeader title="Terminal" code={INSTALL_COMMAND} />
          </div>
        </TimelineStep>

        <TimelineStep number={2}>
          <div className="flex flex-col gap-1.5">
            <h4 className="comet-body-s-accented">
              Open your coding agent and paste this prompt
            </h4>
            <p className="comet-body-xs text-muted-slate">
              Navigate to the repo you want to instrument and paste this prompt.
              It will instrument your agent with Opik tracing automatically.
            </p>
            <CodeBlockWithHeader
              title="Prompt"
              code={displayPromptText}
              copyText={promptText}
            />
          </div>
        </TimelineStep>

        <TimelineStep isLast>
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-s-accented text-primary">
              Waiting for first trace…
            </h4>
            <p className="comet-body-xs text-muted-slate">
              Connect your agent to Opik for observability, evaluation and
              optimization.{" "}
              <a
                href={buildDocsUrl("/faq#troubleshooting")}
                target="_blank"
                rel="noopener noreferrer"
                className="underline"
              >
                Why isn&apos;t my trace showing?
              </a>
            </p>
          </div>
        </TimelineStep>
      </div>
    </div>
  );
};

export default InstallWithAITab;
