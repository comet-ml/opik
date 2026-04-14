import React from "react";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import { useUserApiKey, useActiveWorkspaceName } from "@/store/AppStore";
import { buildDocsUrl, maskAPIKey } from "@/lib/utils";
import { BASE_API_URL } from "@/api/api";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";

const INSTALL_COMMAND = "pip install opik";

interface ConnectToOllieTabProps {
  connected: boolean;
}

const ConnectToOllieTab: React.FC<ConnectToOllieTabProps> = ({ connected }) => {
  const { agentName } = useAgentOnboarding();
  const apiKey = useUserApiKey();
  const workspaceName = useActiveWorkspaceName();

  const buildEnvVars = (shouldMaskAPIKey: boolean) => {
    if (apiKey) {
      const displayedKey = shouldMaskAPIKey ? maskAPIKey(apiKey) : apiKey;
      return `export OPIK_API_KEY="${displayedKey}"\nexport OPIK_WORKSPACE="${workspaceName}"`;
    }
    const url = new URL(BASE_API_URL, window.location.origin).toString();
    return `export OPIK_URL_OVERRIDE="${url}"`;
  };

  const connectCommandText = `${buildEnvVars(
    false,
  )}\nopik connect --project "${agentName}"`;
  const displayConnectCommandText = `${buildEnvVars(
    true,
  )}\nopik connect --project "${agentName}"`;

  return (
    <div className="flex flex-col gap-4 px-1">
      <p className="comet-body-s text-muted-slate">
        Ollie is Opik&apos;s AI coding assistant. When you connect your repo,
        Ollie can inspect your code, help add Opik tracing, and guide you
        through setup.
      </p>
      <div className="flex flex-col">
        <TimelineStep number={1}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">Install Opik</h4>
            <CodeSnippet title="Terminal" code={INSTALL_COMMAND} />
          </div>
        </TimelineStep>

        <TimelineStep number={2}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">
              Connect your repo to Ollie
            </h4>
            <p className="comet-body-xs text-muted-slate">
              Run this command in the repository you want Ollie to work in. This
              creates a local connection between Opik and your machine so Ollie
              can help with setup.
            </p>
            <CodeSnippet
              title="Terminal"
              code={displayConnectCommandText}
              copyText={connectCommandText}
            />
          </div>
        </TimelineStep>

        <TimelineStep isLast completed={connected}>
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-s-accented text-primary">
              {connected
                ? "Repo connected"
                : "Waiting for your repo to connect\u2026"}
            </h4>
            <p className="comet-body-xs text-muted-slate">
              {connected ? (
                "Ollie can now inspect your code and help you set up tracing in Opik."
              ) : (
                <>
                  Run the command above in your repo. Once connected, Ollie can
                  inspect your code and help finish setup.{" "}
                  <a
                    href={buildDocsUrl("/agents/local-runner#troubleshooting")}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="underline hover:text-foreground"
                  >
                    Connect troubleshooting
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

export default ConnectToOllieTab;
