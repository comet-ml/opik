import { useParams } from "@tanstack/react-router";

export const useDatasetIdFromURL = () => {
  return useParams({
    select: (params) => params["datasetId"],
    from: "/workspaceGuard/$workspaceName/datasets/$datasetId",
  });
};
