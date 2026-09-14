import React, { useCallback, useMemo } from "react";

import { useActiveWorkspaceName } from "@/store/AppStore";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import McpRouteTile from "./McpRouteTile";
import McpPromptTile from "./McpPromptTile";
import useMcpPromptContext from "./useMcpPromptContext";
import { buildLocalInstallPrompt } from "./prompt";
import useMcpInstallMode from "./useMcpInstallMode";
import {
  MCP_CLIENT,
  MCP_ROUTE_METHOD,
  McpClient,
  McpInstallRoute,
  McpInstallRoutesProps,
} from "./types";

const CLIENTS: Array<{ client: McpClient; label: string; logo: string }> = [
  {
    client: MCP_CLIENT.CLAUDE_CODE,
    label: "Claude Code",
    logo: claudeCodeLogo,
  },
  { client: MCP_CLIENT.CURSOR, label: "Cursor", logo: cursorLogo },
  { client: MCP_CLIENT.VSCODE, label: "VS Code", logo: vscodeLogo },
  { client: MCP_CLIENT.CODEX, label: "Codex", logo: codexLogo },
];

/**
 * One command per client, for deployments with no hosted MCP server.
 *
 * The CLI is the only route that works here: the local server is a stdio process
 * carrying an API key, so there is nothing a deeplink could safely encode. It
 * also probes the deployment itself and picks the hosted server where one
 * exists, which is why this is a working fallback on Opik Cloud too rather than
 * a degraded one.
 *
 * The workspace is deliberately left to the CLI's own configuration rather than
 * prefixed onto the command: an `OPIK_WORKSPACE=…` prefix is POSIX-only and
 * would simply fail when pasted into PowerShell, and the CLI already refuses,
 * with instructions, when the configured workspace is ambiguous.
 */
const configureCommand = (client: McpClient) =>
  `uvx opik mcp configure --ai-client ${client}`;

const LocalInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = ({
  onRouteUsed,
  traceId,
  projectId,
}) => {
  const installMode = useMcpInstallMode();
  const workspaceName = useActiveWorkspaceName();
  const { projectName } = useMcpPromptContext(projectId);

  const prompt = useMemo(
    () => buildLocalInstallPrompt({ traceId, projectName, workspaceName }),
    [traceId, projectName, workspaceName],
  );

  // Reported here rather than in the popover: this is the only place that knows
  // which server the route installs against and how it gets there.
  const handleUse = useCallback(
    (route: McpInstallRoute) => {
      trackEvent(OpikEvent.MCP_CONNECT_CLICKED, {
        client: route.client,
        method: route.method,
        install_mode: installMode,
      });
      onRouteUsed(route);
    },
    [installMode, onRouteUsed],
  );

  const routes = useMemo<McpInstallRoute[]>(
    () =>
      CLIENTS.map(({ client, label, logo }) => {
        const command = configureCommand(client);
        return {
          client,
          label,
          logo,
          tooltip: `Copies the ${label} setup command. Paste it in your terminal.`,
          method: MCP_ROUTE_METHOD.COPY,
          clipboard: command,
          confirmation: "Copied — paste it in your terminal",
          snippet: command,
        };
      }),
    [],
  );

  return (
    // Wraps at content width rather than an even two-column grid: the labels
    // differ enough in length that equal columns leave "Codex" swimming in
    // padding next to "Claude Code".
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-1.5">
        {routes.map((route) => (
          <McpRouteTile key={route.client} route={route} onUse={handleUse} />
        ))}
      </div>
      <McpPromptTile
        prompt={prompt}
        installMode={installMode}
        onUsed={onRouteUsed}
      />
    </div>
  );
};

export default LocalInstallRoutes;
