import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

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

export default function useDatasetExportJob(
  params: UseDatasetExportJobParams,
  options?: QueryConfig<DatasetExportJob>,
) {
  return useQuery({
    queryKey: ["dataset-export-job", { jobId: params.jobId }],
    queryFn: (context) => getDatasetExportJob(context, params),
    enabled: Boolean(params.jobId),
    ...options,
  });
}
