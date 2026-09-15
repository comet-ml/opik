import React, { useMemo } from "react";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import InstallRoutesLayout from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/InstallRoutesLayout";
import { buildHostedInstallPrompt } from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/prompt";
import {
  cliConfigureCommand,
  getMcpServerUrl,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/serverUrl";
import {
  claudeCodeCommand,
  claudeCodeDeeplink,
  codexCommand,
  cursorDeeplink,
  vscodeDeeplink,
} from "./mcpDeeplinks";
import {
  MCP_CLIENT,
  MCP_ROUTE_METHOD,
  McpInstallRoute,
  McpInstallRoutesProps,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/types";

const NOTHING_OPENED = "Nothing opened? Run this instead.";

const McpInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = (
  props,
) => {
  const routes = useMemo<McpInstallRoute[]>(() => {
    const url = getMcpServerUrl();

    return [
      {
        client: MCP_CLIENT.CLAUDE_CODE,
        label: "Claude Code",
        logo: claudeCodeLogo,
        tooltip:
          "Opens a terminal with the setup command ready. You press Enter.",
        method: MCP_ROUTE_METHOD.DEEPLINK,
        href: claudeCodeDeeplink(url),
        confirmation: "Opening a terminal…",
        snippet: claudeCodeCommand(url),
        // The deeplinked session starts before the server is registered.
        note: `Restart Claude Code once it finishes. ${NOTHING_OPENED}`,
      },
      {
        client: MCP_CLIENT.CURSOR,
        label: "Cursor",
        logo: cursorLogo,
        tooltip: "Opens Cursor and asks you to approve adding the Opik server.",
        method: MCP_ROUTE_METHOD.DEEPLINK,
        href: cursorDeeplink(url),
        confirmation: "Opening Cursor…",
        snippet: cliConfigureCommand(MCP_CLIENT.CURSOR),
        note: NOTHING_OPENED,
      },
      {
        client: MCP_CLIENT.VSCODE,
        label: "VS Code",
        logo: vscodeLogo,
        tooltip: "Opens VS Code and asks you to allow adding the Opik server.",
        method: MCP_ROUTE_METHOD.DEEPLINK,
        href: vscodeDeeplink(url),
        confirmation: "Opening VS Code…",
        snippet: cliConfigureCommand(MCP_CLIENT.VSCODE),
        note: NOTHING_OPENED,
      },
      {
        client: MCP_CLIENT.CODEX,
        label: "Codex",
        logo: codexLogo,
        tooltip: "Copies the setup command. Paste it in your terminal.",
        method: MCP_ROUTE_METHOD.COPY,
        clipboard: codexCommand(url),
        confirmation: "Copied — paste it in your terminal",
        snippet: codexCommand(url),
      },
    ];
  }, []);

  return (
    <InstallRoutesLayout
      {...props}
      routes={routes}
      buildPrompt={buildHostedInstallPrompt}
    />
  );
};

export default McpInstallRoutes;
