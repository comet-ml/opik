/** Values are the keys the Opik CLI accepts for `--ai-client`. */
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
  confirmation: string;
  /** For a deeplink, the recovery path; for a copy, what was copied. */
  snippet?: string;
  note?: string;
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
  workspaceName: string;
  serverUrl: string;
};

export type McpInstallRoutesProps = {
  onRouteUsed: (outcome: McpRouteOutcome) => void;
  target: McpHintTarget;
};
