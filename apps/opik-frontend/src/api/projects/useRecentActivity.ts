import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { RecentActivityResponse } from "@/types/recent-activity";

export const RECENT_ACTIVITY_KEY = "recent-activity";

type UseRecentActivityParams = {
  projectId: string;
  size?: number;
};

const getRecentActivity = async (
  { signal }: QueryFunctionContext,
  { projectId, size = 10 }: UseRecentActivityParams,
) => {
  const { data } = await api.get<RecentActivityResponse>(
    `${PROJECTS_REST_ENDPOINT}${projectId}/activities`,
    {
      signal,
      params: { size },
    },
  );

  return data;
};

export default function useRecentActivity(
  params: UseRecentActivityParams,
  options?: QueryConfig<RecentActivityResponse>,
) {
  return useQuery({
    queryKey: [RECENT_ACTIVITY_KEY, params],
    queryFn: (context) => getRecentActivity(context, params),
    ...options,
  });
}
