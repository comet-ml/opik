import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetExportJob, DATASET_EXPORT_STATUS } from "@/types/datasets";

type UseDatasetExportJobParams = {
  jobId: string;
};

const getDatasetExportJob = async (
  { signal }: QueryFunctionContext,
  { jobId }: UseDatasetExportJobParams,
): Promise<DatasetExportJob> => {
  const { data } = await api.get<DatasetExportJob>(
    `${DATASETS_REST_ENDPOINT}export-jobs/${jobId}`,
    { signal },
  );

  return data;
};

// Polling interval for checking job status (5 seconds)
const POLLING_INTERVAL_MS = 5000;

const isTerminalStatus = (status?: DATASET_EXPORT_STATUS) =>
  status === DATASET_EXPORT_STATUS.COMPLETED ||
  status === DATASET_EXPORT_STATUS.FAILED;

export default function useDatasetExportJob(
  params: UseDatasetExportJobParams,
  options?: QueryConfig<DatasetExportJob>,
) {
  return useQuery({
    queryKey: ["dataset-export-job", params],
    queryFn: (context) => getDatasetExportJob(context, params),
    enabled: Boolean(params.jobId),
    // Poll every 5 seconds until terminal status
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return isTerminalStatus(status) ? false : POLLING_INTERVAL_MS;
    },
    ...options,
  });
}
