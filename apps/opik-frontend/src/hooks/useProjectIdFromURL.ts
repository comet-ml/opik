import { useParams } from "@tanstack/react-router";

export const useProjectIdFromURL = () => {
  return useParams({
    select: (params) => params["projectId"],
    from: "/workspaceGuard/$workspaceName/projects/$projectId",
  });
};
