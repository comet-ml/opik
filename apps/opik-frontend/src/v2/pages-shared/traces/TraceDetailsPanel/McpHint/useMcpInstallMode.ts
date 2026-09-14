import { MCP_INSTALL_MODE, McpInstallMode } from "./types";

/**
 * Which MCP server the routes install against.
 *
 * Single source for every funnel event and for the routes themselves, so the
 * reported mode and the offered route can never disagree.
 */
const useMcpInstallMode = (): McpInstallMode => MCP_INSTALL_MODE.LOCAL;

export default useMcpInstallMode;
