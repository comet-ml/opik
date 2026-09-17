import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

type UseDatasetExportJobsParams = {
  projectId?: string | null;
};

const getDatasetExportJobs = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseDatasetExportJobsParams,
): Promise<DatasetExportJob[]> => {
  const { data } = await api.get<DatasetExportJob[]>(
    `${DATASETS_REST_ENDPOINT}export-jobs`,
    {
      signal,
      params: {
        ...(projectId && { project_id: projectId }),
      },
    },
  );

  return data;
};

export default function useDatasetExportJobs(
  params: UseDatasetExportJobsParams,
  options?: QueryConfig<DatasetExportJob[]>,
) {
  return useQuery({
    queryKey: ["dataset-export-jobs", params],
    queryFn: (context) => getDatasetExportJobs(context, params),
    ...options,
  });
}
