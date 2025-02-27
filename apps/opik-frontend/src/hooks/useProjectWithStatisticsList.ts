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
};

export default function useProjectWithStatisticsList(
  params: UseProjectWithStatisticsParams,
  config: Omit<UseQueryOptions, "queryKey" | "queryFn">,
) {
  const { data: projectsData, isPending } = useProjectsList(params, {
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
    const defaultResponse = { content: [], total: 0 };

    if (!projectsData?.content) {
      return defaultResponse;
    }

    let statisticMap: Record<string, ProjectStatistic> = {};

    if (projectsStatisticData && projectsStatisticData.content && projectsStatisticData.content.length > 0) {
      statisticMap = projectsStatisticData.content.reduce<Record<string, ProjectStatistic>>(
        (acc, statistic) => {
          if (statistic?.project_id) {
            acc[statistic.project_id] = statistic;
          }
          return acc;
        },
        {},
      );
    }

    return {
      ...projectsData,
      content: projectsData.content.map((project) => {
        return project?.id && statisticMap[project.id]
          ? {
              ...project,
              ...statisticMap[project.id],
            }
          : project;
      }),
    };
  }, [projectsData, projectsStatisticData]);

  return {
    data,
    isPending,
  } as UseProjectWithStatisticsResponse;
}
