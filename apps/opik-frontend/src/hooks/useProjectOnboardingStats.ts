import { useQuery, UseQueryResult } from "@tanstack/react-query";
import api, {
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
  PROJECTS_REST_ENDPOINT,
  OPTIMIZATIONS_KEY,
  AGENT_CONFIGS_KEY,
} from "@/api/api";
import { ProjectStats } from "@/types/assistant-sidebar";
import { generateVisibilityFilters, processFilters } from "@/lib/filters";

const VISIBILITY_FILTER_PARAMS = processFilters(
  undefined,
  generateVisibilityFilters(),
);

function useOnboardingCountQuery(
  key: string,
  endpoint: string,
  enabled: boolean,
  params?: Record<string, string>,
): UseQueryResult<number> {
  return useQuery({
    queryKey: [key, "onboarding-count", endpoint, params],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(endpoint, {
        signal,
        params: { size: 1, page: 1, ...params },
      });
      return (data.total as number) ?? 0;
    },
    enabled,
    staleTime: 1_000,
    refetchInterval: 30_000,
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

  const { data: traceTotal, isLoading: tracesLoading } =
    useOnboardingCountQuery(TRACES_KEY, TRACES_REST_ENDPOINT, enabled, {
      project_id: projectId!,
      ...VISIBILITY_FILTER_PARAMS,
    });

  const { data: experimentTotal, isLoading: experimentsLoading } =
    useOnboardingCountQuery(
      "experiments",
      `${PROJECTS_REST_ENDPOINT}${projectId}/experiments`,
      enabled,
    );

  const { data: optimizationTotal, isLoading: optimizationsLoading } =
    useOnboardingCountQuery(
      OPTIMIZATIONS_KEY,
      `${PROJECTS_REST_ENDPOINT}${projectId}/optimizations`,
      enabled,
    );

  const { data: blueprintTotal, isLoading: blueprintsLoading } =
    useOnboardingCountQuery(
      AGENT_CONFIGS_KEY,
      `/v1/private/agent-configs/blueprints/history/projects/${projectId}`,
      enabled,
    );

  if (!enabled) return undefined;

  if (
    tracesLoading ||
    experimentsLoading ||
    optimizationsLoading ||
    blueprintsLoading
  ) {
    return undefined;
  }

  return {
    traceCount: traceTotal ?? 0,
    experimentCount: experimentTotal ?? 0,
    optimizationCount: optimizationTotal ?? 0,
    blueprintVersionCount: blueprintTotal ?? 0,
  };
}
