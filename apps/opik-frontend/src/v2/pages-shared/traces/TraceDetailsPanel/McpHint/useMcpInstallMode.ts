import usePluginsStore from "@/store/PluginsStore";
import { MCP_INSTALL_MODE, McpInstallMode } from "./types";

/**
 * Which MCP server the routes install against.
 *
 * Reads the same signal the routes themselves are selected by, so the reported
 * mode and the offered route can never disagree.
 */
const useMcpInstallMode = (): McpInstallMode =>
  usePluginsStore((state) => state.McpInstallRoutes)
    ? MCP_INSTALL_MODE.HOSTED
    : MCP_INSTALL_MODE.LOCAL;

export default useMcpInstallMode;
