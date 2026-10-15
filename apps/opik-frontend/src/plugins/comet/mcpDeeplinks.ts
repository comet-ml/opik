import { MCP_SERVER_NAME } from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/serverUrl";

// Both shapes match the install links the README and the MCP docs page already
// ship, so there is one form of each to keep working.
//
// Cursor takes the mcp.json entry base64'd into `config`. The empty authority
// is deliberate: `cursor:////` is what the shipped link uses.
export const cursorDeeplink = (url: string) =>
  `cursor:////anysphere.cursor-deeplink/mcp/install?name=${MCP_SERVER_NAME}&config=${btoa(
    JSON.stringify({ url }),
  )}`;

// VS Code goes through its https redirector, which 302s to
// `vscode:mcp/install?<entry>`. Same destination as linking the scheme
// directly, but the browser navigates first, so Chrome offers to open the app
// instead of swallowing the link.
export const vscodeDeeplink = (url: string) =>
  `https://insiders.vscode.dev/redirect/mcp/install?name=${MCP_SERVER_NAME}&config=${encodeURIComponent(
    JSON.stringify({ type: "http", url }),
  )}`;

const claudeCodeCommand = (url: string) =>
  `claude mcp add --transport http --scope user ${MCP_SERVER_NAME} ${url}`;

export const codexCommand = (url: string) =>
  `codex mcp add ${MCP_SERVER_NAME} --url ${url}`;

// Claude Code has no install deeplink. `claude-cli://open` prefills a fresh
// session's prompt box; the `!` makes it a shell command to press Enter on. Its
// OS handler is registered lazily, so the link can do nothing at all, which is
// what the confirmation's fallback prompt is for.
export const claudeCodeDeeplink = (url: string) =>
  `claude-cli://open?q=${encodeURIComponent(`!${claudeCodeCommand(url)}`)}`;
