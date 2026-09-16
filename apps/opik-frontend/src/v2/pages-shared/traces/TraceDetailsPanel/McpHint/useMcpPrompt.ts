import { useMemo } from "react";

import { useActiveWorkspaceName } from "@/store/AppStore";
import useMcpPromptContext from "./useMcpPromptContext";
import { getMcpServerUrl } from "./serverUrl";
import { McpHintTarget, McpPromptContext } from "./types";

// Shared by both deployments: the hosted card offers the prompt beside its
// deeplinks, and the CLI card offers nothing else.
const useMcpPrompt = (
  target: McpHintTarget,
  buildPrompt: (context: McpPromptContext) => string,
): string => {
  const workspaceName = useActiveWorkspaceName();
  const { projectName } = useMcpPromptContext(target.projectId);

  return useMemo(
    () =>
      buildPrompt({
        traceId: target.traceId,
        spanId: target.spanId,
        projectName,
        workspaceName,
        serverUrl: getMcpServerUrl(),
      }),
    [buildPrompt, target.traceId, target.spanId, projectName, workspaceName],
  );
};

export default useMcpPrompt;
