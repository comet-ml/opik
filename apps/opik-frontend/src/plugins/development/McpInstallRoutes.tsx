import React, { lazy, Suspense } from "react";
import { McpInstallRoutesProps } from "@/v2/pages-shared/traces/TraceDetailsPanel/McpHint/types";

// Development runs against a local Opik, which has no hosted MCP server — so the
// slot stays empty and core falls back to the CLI routes, exactly as a
// self-hosted build does. Pointing the dev frontend at a Comet environment
// (`--mode comet`) loads the real comet plugin instead of this one.
const IS_HOSTED_MCP_DEV = Boolean(import.meta.env.VITE_MCP_HOSTED_DEV);

let McpInstallRoutes: React.FC<McpInstallRoutesProps> | undefined;

if (IS_HOSTED_MCP_DEV) {
  const CometMcpInstallRoutes = lazy(
    () => import("@/plugins/comet/McpInstallRoutes"),
  );
  const Wrapped: React.FC<McpInstallRoutesProps> = (props) => (
    <Suspense fallback={null}>
      <CometMcpInstallRoutes {...props} />
    </Suspense>
  );
  Wrapped.displayName = "McpInstallRoutes";
  McpInstallRoutes = Wrapped;
}

export default McpInstallRoutes;
