/**
 * The MCP announcement campaign.
 *
 * This banner is an announcement with an end date, not a permanent fixture
 * (OPIK-8260). Two things can end it: the date below, which needs nobody to
 * remember anything, and a remote switch, which needs somebody to flip it.
 * The date is the only one that works in OSS, where PostHog never initialises.
 */

export const MCP_BANNER_CAMPAIGN_ID = "mcp-announcement-2026-09";

/** Last day the banner may appear, inclusive, in UTC. */
export const MCP_BANNER_CAMPAIGN_END = "2026-11-15";

/**
 * Hide-only kill switch. Has to be created by hand in PostHog before it can
 * be used; until then it resolves to `undefined`, which means "show".
 */
export const MCP_BANNER_FEATURE_FLAG_KEY = "mcp-announcement-banner";

/**
 * Dismissal is per browser and deliberately not scoped per workspace — the
 * announcement is about the product, and a user with three workspaces should
 * not have to dismiss it three times. The version suffix lets a later
 * campaign start from a clean slate instead of inheriting these dismissals.
 */
export const MCP_BANNER_DISMISSED_KEY = "mcp-announcement-dismissed-v1";

/** Impression de-duplication, so a reload does not inflate the funnel. */
export const MCP_BANNER_SHOWN_SESSION_KEY = "mcp-announcement-shown-v1";

export const MCP_BANNER_DOCS_PATH = "/mcp-server";

export const MCP_BANNER_COPY =
  "Opik MCP: debug traces and fix failing evals from Claude Code, Codex, Cursor or VS Code.";

export const MCP_BANNER_COPY_VARIANT = {
  FULL: "full",
  SHORT: "short",
} as const;

export type McpBannerCopyVariant =
  (typeof MCP_BANNER_COPY_VARIANT)[keyof typeof MCP_BANNER_COPY_VARIANT];
