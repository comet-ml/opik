import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetExportJob } from "@/types/datasets";

const getDatasetExportJobs = async ({
  signal,
}: QueryFunctionContext): Promise<DatasetExportJob[]> => {
  const { data } = await api.get<DatasetExportJob[]>(
    `${DATASETS_REST_ENDPOINT}export/jobs`,
    { signal },
  );

  return data;
};

export default function useDatasetExportJobs(
  options?: QueryConfig<DatasetExportJob[]>,
) {
  return useQuery({
    queryKey: ["dataset-export-jobs", {}],
    queryFn: (context) => getDatasetExportJobs(context),
    ...options,
  });
}
