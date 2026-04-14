import { useParams } from "@tanstack/react-router";

export const useSuiteIdFromURL = () => {
  return useParams({
    select: (params) => params["suiteId"],
    from: "/workspaceGuard/$workspaceName/projects/$projectId/test-suites/$suiteId",
  });
};
