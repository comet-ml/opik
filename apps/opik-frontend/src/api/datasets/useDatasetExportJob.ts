import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetExportJob, DATASET_EXPORT_STATUS } from "@/types/datasets";

type UseDatasetExportJobParams = {
  datasetId: string;
  jobId: string;
};

const getDatasetExportJob = async (
  { signal }: QueryFunctionContext,
  { datasetId, jobId }: UseDatasetExportJobParams,
): Promise<DatasetExportJob> => {
  const { data } = await api.get<DatasetExportJob>(
    `${DATASETS_REST_ENDPOINT}${datasetId}/export/${jobId}`,
    { signal },
  );

  return data;
};

// Polling interval for checking job status (5 seconds)
const POLLING_INTERVAL_MS = 5000;

export default function useDatasetExportJob(
  params: UseDatasetExportJobParams,
  options?: QueryConfig<DatasetExportJob>,
) {
  const isTerminalStatus = (status?: DATASET_EXPORT_STATUS) =>
    status === DATASET_EXPORT_STATUS.COMPLETED ||
    status === DATASET_EXPORT_STATUS.FAILED;

  return useQuery({
    queryKey: ["dataset-export-job", params],
    queryFn: (context) => getDatasetExportJob(context, params),
    enabled: Boolean(params.datasetId && params.jobId),
    // Poll every 5 seconds until terminal status
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return isTerminalStatus(status) ? false : POLLING_INTERVAL_MS;
    },
    ...options,
  });
}
