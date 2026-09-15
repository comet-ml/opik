import React, { useMemo } from "react";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import InstallRoutesLayout from "./InstallRoutesLayout";
import { cliConfigureCommand } from "./serverUrl";
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
 * One CLI command per client, for deployments with no hosted MCP server.
 *
 * These are the install route here, not a convenience: a local server is a
 * stdio process holding an API key, which no deeplink can encode, and the CLI
 * is what reads that key from the developer's own configuration or asks for it
 * in their terminal. The prompt beside them hands the same job to an agent,
 * which cannot answer for the key when there is no configuration yet.
 *
 * The workspace is left to the CLI's own configuration: an `OPIK_WORKSPACE=`
 * prefix is POSIX-only and would fail when pasted into PowerShell.
 */
const CliInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = (
  props,
) => {
  const routes = useMemo<McpInstallRoute[]>(
    () =>
      CLIENTS.map(({ client, label, logo }) => {
        const command = cliConfigureCommand(client);
        return {
          client,
          label,
          logo,
          tooltip: `Copies the ${label} setup command. Paste it in your terminal.`,
          method: MCP_ROUTE_METHOD.COPY,
          kind: "copied" as const,
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

export default CliInstallRoutes;
