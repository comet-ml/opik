import React from "react";
import { ExternalLink } from "lucide-react";

import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";
import { INSTALL_OPIK_SKILLS_COMMAND } from "@/constants/shared";
import useActiveProjectName from "@/hooks/useActiveProjectName";
import emptyAgentConfigLightUrl from "/images/empty-agent-configuration-light.svg";
import emptyAgentConfigDarkUrl from "/images/empty-agent-configuration-dark.svg";

const AgentConfigurationEmptyState: React.FC = () => {
  const { themeMode } = useTheme();
  const projectName = useActiveProjectName();
  const agentPrompt = `Add Opik agent configuration to the project "${projectName}". Define a config schema with my agent's key parameters (such as model, temperature or prompts), and publish the first version`;
  const imageUrl =
    themeMode === THEME_MODE.DARK
      ? emptyAgentConfigDarkUrl
      : emptyAgentConfigLightUrl;

  return (
    <div className="flex min-h-full flex-1 items-center justify-center gap-16 px-6">
      <div className="w-full max-w-lg">
        <h2 className="comet-title-s mb-6">No agent configuration yet</h2>

        <div className="flex flex-col">
          <TimelineStep number={1}>
            <div className="flex flex-col gap-2">
              <h4 className="comet-body-s-accented">
                Install the Opik skills package
              </h4>
              <CodeSnippet
                title="Terminal"
                code={INSTALL_OPIK_SKILLS_COMMAND}
              />
            </div>
          </TimelineStep>

          <TimelineStep number={2}>
            <div className="flex flex-col gap-2">
              <h4 className="comet-body-s-accented">
                Ask your coding agent to instrument app
              </h4>
              <CodeSnippet title="Prompt" code={agentPrompt} />
            </div>
          </TimelineStep>

          <TimelineStep number={3} isLast>
            <div className="flex flex-col gap-1">
              <h4 className="comet-body-s-accented">Run your agent</h4>
              <p className="comet-body-xs text-muted-slate">
                Your configuration will appear here automatically after your
                next trace is logged.
              </p>
            </div>
          </TimelineStep>
        </div>

        <Button variant="outline" size="sm" asChild>
          <a
            // TODO: Add correct URL when docs ready
            href={buildDocsUrl()}
            target="_blank"
            rel="noreferrer"
          >
            View docs
            <ExternalLink className="ml-1.5 size-3.5" />
          </a>
        </Button>
      </div>

      <div className="hidden shrink-0 items-start pt-8 xl:flex">
        <img
          src={imageUrl}
          alt="Agent configuration"
          className="max-h-[280px]"
        />
      </div>
    </div>
  );
};

export default AgentConfigurationEmptyState;
