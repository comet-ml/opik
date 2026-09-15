/**
 * The coding agents the hint offers a route to. The values are the keys the Opik
 * CLI accepts for `--ai-client`, so the analytics join against SDK-side events
 * without a lookup table in between.
 */
export const MCP_CLIENT = {
  CLAUDE_CODE: "claude-code",
  CURSOR: "cursor",
  VSCODE: "vscode",
  CODEX: "codex",
} as const;

export type McpClient = (typeof MCP_CLIENT)[keyof typeof MCP_CLIENT];

/** What a route actually does when used — reported so we can tell which converts. */
export const MCP_ROUTE_METHOD = {
  DEEPLINK: "deeplink",
  COPY: "copy",
} as const;

export type McpRouteMethod =
  (typeof MCP_ROUTE_METHOD)[keyof typeof MCP_ROUTE_METHOD];

/** Which server the routes install against. Reported on every funnel event. */
export const MCP_INSTALL_MODE = {
  HOSTED: "hosted",
  LOCAL: "local",
} as const;

export type McpInstallMode =
  (typeof MCP_INSTALL_MODE)[keyof typeof MCP_INSTALL_MODE];

/** What the popover shows once the user has left through one of the routes. */
export type McpRouteOutcome = {
  /** Shown after the route is used. */
  confirmation: string;
  /**
   * Repeated under the confirmation. For a deeplink this is the recovery path
   * when nothing opened; for a copy it is the command that was copied, so the
   * user can read it before pasting.
   */
  snippet?: string;
  /** Extra line under the snippet — the restart warning, the "nothing opened?" note. */
  note?: string;
};

export type McpInstallRoute = McpRouteOutcome & {
  client: McpClient;
  label: string;
  logo: string;
  tooltip: string;
  method: McpRouteMethod;
  /** Deeplink routes only. */
  href?: string;
  /** What lands on the clipboard for a copy route. */
  clipboard?: string;
};

/** Reported on the funnel events so trace-level and span-level failures stay separable. */
export type McpHintEntityType = "trace" | "span";

/** The failure the hint is about. One value because these always travel together. */
export type McpHintTarget = {
  traceId: string;
  projectId: string;
  entityType: McpHintEntityType;
};

/** Everything a prompt may need; each builder takes the same shape and uses what it needs. */
export type McpPromptContext = {
  traceId: string;
  projectName: string;
  workspaceName: string;
  serverUrl: string;
};

export type McpInstallRoutesProps = {
  /** Called with the outcome of the route the user took, so the popover can confirm it. */
  onRouteUsed: (outcome: McpRouteOutcome) => void;
  target: McpHintTarget;
};
