import { useParams } from "@tanstack/react-router";

export const usePromptIdFromURL = () => {
  return useParams({
    select: (params) => params["promptId"],
    from: "/workspaceGuard/$workspaceName/prompts/$promptId",
  });
};
