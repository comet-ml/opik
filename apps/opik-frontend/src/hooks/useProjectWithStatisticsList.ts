import { keepPreviousData, UseQueryOptions } from "@tanstack/react-query";
import { Sorting } from "@/types/sorting";
import { ProjectWithStatistic } from "@/types/projects";
import useProjectsList from "@/api/projects/useProjectsList";

type UseProjectWithStatisticsParams = {
  workspaceName: string;
  search?: string;
  sorting?: Sorting;
  page: number;
  size: number;
};

type UseProjectWithStatisticsResponse = {
  data: {
    content: ProjectWithStatistic[];
    total: number;
  };
  isPending: boolean;
};

export default function useProjectWithStatisticsList(
  params: UseProjectWithStatisticsParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  // Single API call to the backend
  // The backend's find() method already delegates to getStats() when sorting by metrics
  // This ensures we get both project metadata AND statistics in a single response
  const { data, isPending } = useProjectsList(params, {
    ...config,
    placeholderData: keepPreviousData,
  } as never);

  return {
    data: data || { content: [], total: 0 },
    isPending,
  } as UseProjectWithStatisticsResponse;
}
