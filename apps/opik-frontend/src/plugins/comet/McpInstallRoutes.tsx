import React, { useCallback, useMemo } from "react";

import claudeCodeLogo from "/images/integrations/claude_code.svg";
import codexLogo from "/images/integrations/codex.svg";
import cursorLogo from "/images/integrations/cursor.svg";
import vscodeLogo from "/images/integrations/vscode.svg";

import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import McpRouteTile from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/McpRouteTile";
import useMcpInstallMode from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/useMcpInstallMode";
import {
  getMcpServerUrl,
  MCP_SERVER_NAME,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/serverUrl";
import {
  MCP_CLIENT,
  MCP_ROUTE_METHOD,
  McpInstallRoute,
  McpInstallRoutesProps,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/types";

// Only two of the four clients have a real one-click MCP install deeplink.
//
// Cursor: documented as `cursor://anysphere.cursor-deeplink/mcp/install`, with
// the mcp.json entry base64'd into `config`.
const cursorDeeplink = (url: string) =>
  `cursor://anysphere.cursor-deeplink/mcp/install?name=${MCP_SERVER_NAME}&config=${btoa(
    JSON.stringify({ url }),
  )}`;

// VS Code: one unnamed query parameter carrying the whole entry, `name`
// included — not the older `?name=&config=` badge shape.
const vscodeDeeplink = (url: string) =>
  `vscode:mcp/install?${encodeURIComponent(
    JSON.stringify({ name: MCP_SERVER_NAME, type: "http", url }),
  )}`;

const claudeCodeCommand = (url: string) =>
  `claude mcp add --transport http --scope user ${MCP_SERVER_NAME} ${url}`;

const codexCommand = (url: string) =>
  `codex mcp add ${MCP_SERVER_NAME} --url ${url}`;

// Claude Code has no install deeplink at all. `claude-cli://open` prefills the
// prompt box of a fresh session — the `!` makes it a shell command the user only
// has to press Enter on. Its OS handler is also registered lazily, on the user's
// first interactive prompt, so the link can do nothing at all; that is why every
// tile lands in a confirmation carrying the command as well.
const claudeCodeDeeplink = (url: string) =>
  `claude-cli://open?q=${encodeURIComponent(`!${claudeCodeCommand(url)}`)}`;

const NOTHING_OPENED = "Nothing opened? Run this instead.";

const McpInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = ({
  onRouteUsed,
}) => {
  const installMode = useMcpInstallMode();

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
        // The deeplinked session starts before the server is registered, so it
        // will not have loaded it — without this the user concludes it is broken.
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
        snippet: `uvx opik mcp configure --ai-client ${MCP_CLIENT.CURSOR}`,
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
        snippet: `uvx opik mcp configure --ai-client ${MCP_CLIENT.VSCODE}`,
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

  return (
    <div className="flex flex-wrap gap-1.5">
      {routes.map((route) => (
        <McpRouteTile key={route.client} route={route} onUse={handleUse} />
      ))}
    </div>
  );
};

export default McpInstallRoutes;
