import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceCost } from "@/types/workspaces";
import dayjs from "dayjs";

type UseWorkspaceCostParams = {
  projectIds: string[];
  intervalStart: string;
  intervalEnd: string;
};

interface WorkspaceCostResponse {
  results: WorkspaceCost[];
}

const getWorkspaceCost = async (
  { signal }: QueryFunctionContext,
  { projectIds, intervalStart, intervalEnd }: UseWorkspaceCostParams,
) => {
  const { data } = await api.post<WorkspaceCostResponse>(
    `${WORKSPACES_REST_ENDPOINT}costs`,
    {
      ...(projectIds.length > 0 && { project_ids: projectIds }),
      interval_start: intervalStart,
      interval_end: intervalEnd,
    },
    {
      signal,
      validateStatus: (status) => status === 200 || status === 404, // TODO lala delete this line when backend is ready
    },
  );

  // TODO lala remove mock data

  // Simulate network delay for demo purposes
  await new Promise((resolve) =>
    setTimeout(resolve, Math.floor(Math.random() * (3000 - 200 + 1)) + 200),
  );

  const generateData = () => {
    const retVal = [];
    const days = dayjs(intervalEnd).diff(dayjs(intervalStart), "day");

    // Randomly return empty array to simulate BE response
    if (Math.random() < 0.1) {
      return [];
    }

    for (let i = 0; i <= days; i++) {
      if (Math.random() > 0.05) {
        retVal.push({
          time: dayjs(intervalStart).add(i, "day").toISOString(),
          value: Math.random() * 1000000000,
        });
      }
    }

    return retVal;
  };

  if (projectIds.length === 0) {
    return [
      {
        project_id: null,
        name,
        data: generateData(),
      },
    ];
  } else {
    return projectIds.map((projectId) => {
      return {
        project_id: projectId,
        name,
        data: generateData(),
      };
    });
  }

  return data?.results;
};

const useWorkspaceCost = (
  params: UseWorkspaceCostParams,
  config?: QueryConfig<WorkspaceCost[]>,
) => {
  return useQuery({
    queryKey: ["workspace-costs", params],
    queryFn: (context) => getWorkspaceCost(context, params),
    ...config,
  });
};

export default useWorkspaceCost;
