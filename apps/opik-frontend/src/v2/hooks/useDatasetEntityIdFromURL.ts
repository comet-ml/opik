import { useParams } from "@tanstack/react-router";

export const useDatasetEntityIdFromURL = () => {
  return useParams({
    strict: false,
    select: (params) =>
      (params as Record<string, string>)["datasetId"] ||
      (params as Record<string, string>)["suiteId"],
  });
};
