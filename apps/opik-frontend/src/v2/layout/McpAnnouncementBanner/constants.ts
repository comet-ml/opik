export const MCP_BANNER_CAMPAIGN_ID = "mcp-announcement-2026-09";

// Last day the banner may appear, inclusive, UTC. The only off switch in OSS.
export const MCP_BANNER_CAMPAIGN_END = "2026-11-15";

// PostHog boolean. Hide-only: `false` hides, `true`/unresolved shows.
export const MCP_BANNER_FEATURE_FLAG_KEY = "mcp-announcement-banner";

// Per browser, not per workspace. The suffix lets a later campaign start clean.
export const MCP_BANNER_DISMISSED_KEY = "mcp-announcement-dismissed-v1";

export const MCP_BANNER_SHOWN_SESSION_KEY = "mcp-announcement-shown-v1";

export const MCP_BANNER_DOCS_PATH = "/mcp-server";

// Matches the h-8 in the markup; the layout is told this before the bar is measured.
export const MCP_BANNER_HEIGHT = 32;

export const MCP_BANNER_COPY =
  "Opik MCP: debug traces and fix failing evals from Claude Code or Codex.";

export const MCP_BANNER_COPY_SHORT = "Opik MCP: debug traces with your agent.";

export const MCP_BANNER_COPY_VARIANT = {
  FULL: "full",
  SHORT: "short",
} as const;

export type McpBannerCopyVariant =
  (typeof MCP_BANNER_COPY_VARIANT)[keyof typeof MCP_BANNER_COPY_VARIANT];
