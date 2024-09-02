import { useParams } from "@tanstack/react-router";

export const useWorkspaceNameFromURL = () => {
  return useParams({
    strict: false,
    select: (params) => params["workspaceName"],
  });
};
