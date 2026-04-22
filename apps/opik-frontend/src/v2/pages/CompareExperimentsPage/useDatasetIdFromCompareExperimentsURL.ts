import { useParams } from "@tanstack/react-router";

export const useDatasetIdFromCompareExperimentsURL = () => {
  return useParams({
    select: (params) => params["datasetId"],
    from: "/workspaceGuard/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
  });
};
