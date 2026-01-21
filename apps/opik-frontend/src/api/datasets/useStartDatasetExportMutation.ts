import { useMutation } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

type UseStartDatasetExportMutationParams = {
  datasetId: string;
  versionId?: string;
};

const startDatasetExport = async ({
  datasetId,
  versionId,
}: UseStartDatasetExportMutationParams): Promise<DatasetExportJob> => {
  const url = new URL(
    `${DATASETS_REST_ENDPOINT}${datasetId}/export`,
    window.location.origin,
  );
  if (versionId) {
    url.searchParams.set("versionId", versionId);
  }

  const { data } = await api.post<DatasetExportJob>(url.pathname + url.search);

  return data;
};

export default function useStartDatasetExportMutation() {
  return useMutation({
    mutationFn: startDatasetExport,
  });
}
