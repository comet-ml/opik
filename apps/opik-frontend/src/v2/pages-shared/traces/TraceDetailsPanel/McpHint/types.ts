/**
 * The clients the card offers a tile for. Values are the keys the Opik CLI
 * accepts for `--ai-client`, so analytics join against SDK-side events without
 * a lookup table — but this is a subset of them: the CLI also takes `opencode`,
 * which has no logo in the repo to put on a tile. The prompt route covers it,
 * and every other client we never enumerated.
 */
export const MCP_CLIENT = {
  CLAUDE_CODE: "claude-code",
  CURSOR: "cursor",
  VSCODE: "vscode",
  CODEX: "codex",
} as const;

export type McpClient = (typeof MCP_CLIENT)[keyof typeof MCP_CLIENT];

export const MCP_ROUTE_METHOD = {
  DEEPLINK: "deeplink",
  COPY: "copy",
} as const;

export type McpRouteMethod =
  (typeof MCP_ROUTE_METHOD)[keyof typeof MCP_ROUTE_METHOD];

export const MCP_INSTALL_MODE = {
  HOSTED: "hosted",
  LOCAL: "local",
} as const;

export type McpInstallMode =
  (typeof MCP_INSTALL_MODE)[keyof typeof MCP_INSTALL_MODE];

/** What the popover shows once the user has left through one of the routes. */
export type McpRouteOutcome = {
  /**
   * A copy is done, so the card can get out of the way. An opened deeplink is
   * not: the hand-off cannot be observed from here, so that view stands and
   * carries the fallback.
   */
  kind: "copied" | "opened";
  confirmation: string;
  /** Sits above the snippet. */
  note?: string;
  /** For a deeplink, the fallback to use; for a copy, what was copied. */
  snippet?: string;
};

export type McpInstallRoute = McpRouteOutcome & {
  client: McpClient;
  label: string;
  logo: string;
  tooltip: string;
  method: McpRouteMethod;
  href?: string;
  clipboard?: string;
};

export type McpHintEntityType = "trace" | "span";

export type McpHintTarget = {
  traceId: string;
  /** The span being inspected, when the failure is a span's rather than the trace's. */
  spanId?: string;
  projectId: string;
  entityType: McpHintEntityType;
};

export type McpPromptContext = {
  traceId: string;
  spanId?: string;
  projectName: string;
  projectId: string;
  workspaceName: string;
  serverUrl: string;
};

export type McpInstallRoutesProps = {
  onRouteUsed: (outcome: McpRouteOutcome) => void;
  /**
   * A copy landed. It changes nothing about the card, but the user did leave
   * through it, so closing it afterwards is not abandonment.
   */
  onCopied: () => void;
  target: McpHintTarget;
};
