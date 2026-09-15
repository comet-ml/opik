import React from "react";

import McpPromptBlock from "./McpPromptBlock";
import useMcpInstallMode from "./useMcpInstallMode";
import useMcpPrompt from "./useMcpPrompt";
import { buildLocalInstallPrompt } from "./prompt";
import { McpInstallRoutesProps } from "./types";

/**
 * The prompt, and nothing else, for deployments with no hosted MCP server.
 *
 * There is no deeplink to offer: a local server is a stdio process holding an
 * API key, which no URL describes. That leaves `opik mcp configure`, which the
 * prompt has the agent run — one route rather than one tile per client that all
 * copied a variation of the same command.
 */
const CliInstallRoutes: React.FunctionComponent<McpInstallRoutesProps> = ({
  target,
  isCopied,
  onCopied,
}) => {
  const installMode = useMcpInstallMode();
  const prompt = useMcpPrompt(target, buildLocalInstallPrompt);

  return (
    <McpPromptBlock
      prompt={prompt}
      installMode={installMode}
      entityType={target.entityType}
      isCopied={isCopied}
      onCopied={onCopied}
    />
  );
};

export default CliInstallRoutes;
