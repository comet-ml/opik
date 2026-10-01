import usePluginsStore from "@/store/PluginsStore";
import { MCP_INSTALL_MODE, McpInstallMode } from "./types";

// Reads the same signal the routes are selected by, so the reported mode and
// the offered route cannot disagree.
const useMcpInstallMode = (): McpInstallMode =>
  usePluginsStore((state) => state.McpInstallRoutes)
    ? MCP_INSTALL_MODE.HOSTED
    : MCP_INSTALL_MODE.LOCAL;

export default useMcpInstallMode;
