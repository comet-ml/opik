import { useMutation } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

type UseStartDatasetExportMutationParams = {
  datasetId: string;
};

const startDatasetExport = async ({
  datasetId,
}: UseStartDatasetExportMutationParams): Promise<DatasetExportJob> => {
  const { data } = await api.post<DatasetExportJob>(
    `${DATASETS_REST_ENDPOINT}${datasetId}/export`,
  );

  return data;
};

export default function useStartDatasetExportMutation() {
  return useMutation({
    mutationFn: startDatasetExport,
  });
}
