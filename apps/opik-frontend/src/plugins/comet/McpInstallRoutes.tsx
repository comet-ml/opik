import React, { useMemo } from "react";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import InstallRoutesLayout from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/InstallRoutesLayout";
import { buildHostedInstallPrompt } from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/prompt";
import { getMcpServerUrl } from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/serverUrl";
import {
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
        kind: "opened" as const,
        confirmation: "Opening a terminal…",
      },
      {
        client: MCP_CLIENT.CURSOR,
        label: "Cursor",
        logo: cursorLogo,
        tooltip: "Opens Cursor and asks you to approve adding the Opik server.",
        method: MCP_ROUTE_METHOD.DEEPLINK,
        href: cursorDeeplink(url),
        kind: "opened" as const,
        confirmation: "Opening Cursor…",
      },
      {
        client: MCP_CLIENT.VSCODE,
        label: "VS Code",
        logo: vscodeLogo,
        tooltip: "Opens VS Code and asks you to allow adding the Opik server.",
        method: MCP_ROUTE_METHOD.DEEPLINK,
        href: vscodeDeeplink(url),
        kind: "opened" as const,
        confirmation: "Opening VS Code…",
      },
      {
        client: MCP_CLIENT.CODEX,
        label: "Codex",
        logo: codexLogo,
        tooltip: "Copies the setup command. Paste it in your terminal.",
        method: MCP_ROUTE_METHOD.COPY,
        clipboard: codexCommand(url),
        kind: "copied" as const,
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
