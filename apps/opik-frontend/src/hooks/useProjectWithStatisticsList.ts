import { useMemo } from "react";
import { keepPreviousData, UseQueryOptions } from "@tanstack/react-query";
import { Sorting } from "@/types/sorting";
import { ProjectStatistic, ProjectWithStatistic } from "@/types/projects";
import useProjectsList from "@/api/projects/useProjectsList";
import useProjectStatisticsList from "@/api/projects/useProjectStatisticList";

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
  isPlaceholderData: boolean;
  isFetching: boolean;
};

export default function useProjectWithStatisticsList(
  params: UseProjectWithStatisticsParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const {
    data: projectsData,
    isPending,
    isPlaceholderData,
    isFetching,
  } = useProjectsList(params, {
    ...config,
    placeholderData: keepPreviousData,
  } as never);

  const { data: projectsStatisticData } = useProjectStatisticsList(
    {
      ...params,
    },
    {
      ...config,
      placeholderData: keepPreviousData,
    } as never,
  );

  const data = useMemo(() => {
    if (projectsData) {
      let statisticMap: Record<string, ProjectStatistic> = {};

      if (projectsStatisticData && projectsStatisticData.content?.length > 0) {
        statisticMap = projectsStatisticData.content.reduce<
          Record<string, ProjectStatistic>
        >((acc, statistic) => {
          acc[statistic.project_id!] = statistic;
          return acc;
        }, {});
      }

      return {
        ...projectsData,
        content:
          projectsData.content?.map((project) => {
            return statisticMap
              ? {
                  ...project,
                  ...statisticMap[project.id],
                }
              : project;
          }) || [],
      };
    }

    return { content: [], total: 0 };
  }, [projectsData, projectsStatisticData]);

  return {
    data,
    isPending,
    isPlaceholderData,
    isFetching,
  } as UseProjectWithStatisticsResponse;
}
