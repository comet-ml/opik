import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DASHBOARDS_REST_ENDPOINT, EXPERIMENT_DASHBOARD_KEY, QueryConfig } from "@/api/api";

export interface ExperimentDashboard {
  experiment_id: string;
  dashboard_id: string;
  workspace_id: string;
  created_at: string;
  created_by: string;
  last_updated_at: string;
  last_updated_by: string;
}

type UseExperimentDashboardParams = {
  experimentId: string;
};

const getExperimentDashboard = async (
  { signal }: QueryFunctionContext,
  { experimentId }: UseExperimentDashboardParams,
): Promise<ExperimentDashboard | null> => {
  try {
    const { data } = await api.get<ExperimentDashboard>(
      `${DASHBOARDS_REST_ENDPOINT}experiments/${experimentId}`,
      { signal }
    );

    return data;
  } catch (error: any) {
    // If no dashboard is associated with the experiment, return null instead of throwing
    if (error?.response?.status === 404) {
      return null;
    }
    // Re-throw other errors
    throw error;
  }
};

export const useExperimentDashboard = (
  params: UseExperimentDashboardParams,
  options?: QueryConfig<ExperimentDashboard | null>,
) => {
  return useQuery({
    queryKey: [EXPERIMENT_DASHBOARD_KEY, params],
    queryFn: (context) => getExperimentDashboard(context, params),
    ...options,
  });
}; 
