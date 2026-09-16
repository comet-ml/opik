import { useMutation } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

type UseStartExperimentItemsExportMutationParams = {
  datasetId: string;
  experimentsIds: string[];
};

const startExperimentItemsExport = async ({
  datasetId,
  experimentsIds,
}: UseStartExperimentItemsExportMutationParams): Promise<DatasetExportJob> => {
  const { data } = await api.post<DatasetExportJob>(
    `${DATASETS_REST_ENDPOINT}${datasetId}/experiments/export`,
    undefined,
    {
      params: {
        experiment_ids: JSON.stringify(experimentsIds),
      },
    },
  );

  return data;
};

export default function useStartExperimentItemsExportMutation() {
  return useMutation({
    mutationFn: startExperimentItemsExport,
  });
}
