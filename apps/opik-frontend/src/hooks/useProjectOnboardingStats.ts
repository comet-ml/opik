import { useQuery, UseQueryResult } from "@tanstack/react-query";
import api, {
  PROJECTS_REST_ENDPOINT,
  OPTIMIZATIONS_KEY,
  AGENT_CONFIGS_KEY,
  PROJECT_STATISTICS_KEY,
} from "@/api/api";
import { ProjectStats } from "@/types/assistant-sidebar";
import { generateProjectFilters, processFilters } from "@/lib/filters";

function useOnboardingCountQuery(
  key: string,
  endpoint: string,
  enabled: boolean,
): UseQueryResult<number> {
  return useQuery({
    queryKey: [key, "onboarding-count", endpoint],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(endpoint, {
        signal,
        params: { size: 1, page: 1 },
      });
      return (data.total as number) ?? 0;
    },
    enabled,
    staleTime: 1_000,
  });
}

/**
 * Lightweight counts used by the assistant sidebar to understand
 * where the user is in their onboarding journey. Each query fetches
 * size=1 and reads the `total` field, minimizing data transfer.
 *
 * Returns `undefined` when no projectId is provided so the caller
 * can distinguish "no project" from "zero activity".
 */
export default function useProjectOnboardingStats(
  projectId: string | null | undefined,
): ProjectStats | undefined {
  const enabled = !!projectId;

  const { data: statsData } = useQuery({
    queryKey: [PROJECT_STATISTICS_KEY, "onboarding-trace-count", projectId],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(`${PROJECTS_REST_ENDPOINT}stats`, {
        signal,
        params: {
          size: 1,
          page: 1,
          ...processFilters(generateProjectFilters(projectId!)),
        },
      });
      const match = (data.content ?? []).find(
        (s: { project_id?: string }) => s.project_id === projectId,
      );
      return (match?.trace_count as number) ?? 0;
    },
    enabled,
    staleTime: 1_000,
  });

  const { data: experimentTotal } = useOnboardingCountQuery(
    "experiments",
    `${PROJECTS_REST_ENDPOINT}${projectId}/experiments`,
    enabled,
  );

  const { data: optimizationTotal } = useOnboardingCountQuery(
    OPTIMIZATIONS_KEY,
    `${PROJECTS_REST_ENDPOINT}${projectId}/optimizations`,
    enabled,
  );

  const { data: blueprintTotal } = useOnboardingCountQuery(
    AGENT_CONFIGS_KEY,
    `/v1/private/agent-configs/blueprints/history/projects/${projectId}`,
    enabled,
  );

  if (!enabled) return undefined;

  return {
    traceCount: statsData ?? 0,
    experimentCount: experimentTotal ?? 0,
    optimizationCount: optimizationTotal ?? 0,
    blueprintVersionCount: blueprintTotal ?? 0,
  };
}
