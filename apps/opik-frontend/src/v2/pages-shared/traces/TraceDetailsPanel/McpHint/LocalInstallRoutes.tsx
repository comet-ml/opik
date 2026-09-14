import React, { useMemo } from "react";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import InstallRoutesLayout from "./InstallRoutesLayout";
import { buildLocalInstallPrompt } from "./prompt";
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
 * The CLI is the only route that works here: the local server is a stdio
 * process carrying an API key, so there is nothing a deeplink could safely
 * encode. It also probes the deployment itself and picks the hosted server
 * where one exists, which is why this is a working fallback on Opik Cloud too
 * rather than a degraded one.
 *
 * The workspace is left to the CLI's own configuration rather than prefixed
 * onto the command: an `OPIK_WORKSPACE=…` prefix is POSIX-only and would simply
 * fail when pasted into PowerShell, and the CLI already refuses, with
 * instructions, when the configured workspace is ambiguous. The prompt route
 * names it instead, where prose can.
 */
const configureCommand = (client: McpClient) =>
  `uvx opik mcp configure --ai-client ${client}`;

const LocalInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = (
  props,
) => {
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
    <InstallRoutesLayout
      {...props}
      routes={routes}
      buildPrompt={buildLocalInstallPrompt}
    />
  );
};

export default LocalInstallRoutes;
