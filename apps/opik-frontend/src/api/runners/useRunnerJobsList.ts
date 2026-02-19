import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  RUNNER_JOBS_KEY,
  RUNNERS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { RunnerJob } from "@/types/runners";

type UseRunnerJobsListParams = {
  runnerId: string;
  project?: string;
};

const getRunnerJobsList = async (
  { signal }: QueryFunctionContext,
  { runnerId, project }: UseRunnerJobsListParams,
) => {
  const { data } = await api.get<RunnerJob[]>(
    `${RUNNERS_REST_ENDPOINT}${runnerId}/jobs`,
    {
      signal,
      params: project ? { project } : undefined,
    },
  );
  return data;
};

export default function useRunnerJobsList(
  params: UseRunnerJobsListParams,
  options?: QueryConfig<RunnerJob[]>,
) {
  return useQuery({
    queryKey: [RUNNER_JOBS_KEY, params],
    queryFn: (context) => getRunnerJobsList(context, params),
    ...options,
  });
}
