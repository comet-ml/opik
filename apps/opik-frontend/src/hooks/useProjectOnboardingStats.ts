import { useMemo } from "react";
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

  const { data: traceTotal, isFetched: tracesFetched } =
    useOnboardingCountQuery(TRACES_KEY, TRACES_REST_ENDPOINT, enabled, {
      project_id: projectId!,
      ...VISIBILITY_FILTER_PARAMS,
    });

  const { data: experimentTotal, isFetched: experimentsFetched } =
    useOnboardingCountQuery(
      "experiments",
      `${PROJECTS_REST_ENDPOINT}${projectId}/experiments`,
      enabled,
    );

  const { data: optimizationTotal, isFetched: optimizationsFetched } =
    useOnboardingCountQuery(
      OPTIMIZATIONS_KEY,
      `${PROJECTS_REST_ENDPOINT}${projectId}/optimizations`,
      enabled,
    );

  const { data: blueprintTotal, isFetched: blueprintsFetched } =
    useOnboardingCountQuery(
      AGENT_CONFIGS_KEY,
      `/v1/private/agent-configs/blueprints/history/projects/${projectId}`,
      enabled,
    );

  // Memoize so consumers (e.g. the bridge context) get a stable reference
  // across refetches when values haven't changed. Without this, every 30s
  // refetch re-renders the hook, returns a new object, and cascades into a
  // `context:changed` emit to the Ollie iframe — causing a visible blink.
  const stats = useMemo<ProjectStats>(
    () => ({
      traceCount: traceTotal ?? 0,
      experimentCount: experimentTotal ?? 0,
      optimizationCount: optimizationTotal ?? 0,
      blueprintVersionCount: blueprintTotal ?? 0,
    }),
    [traceTotal, experimentTotal, optimizationTotal, blueprintTotal],
  );

  if (!enabled) return undefined;

  // Wait for every query's first fetch to complete (success OR error).
  // Using `isFetched` instead of `isLoading` avoids a flicker back to
  // `undefined` on each refetch when a query has errored (no cached data),
  // which would otherwise happen every 30s and cause the Ollie iframe to blink.
  if (
    !tracesFetched ||
    !experimentsFetched ||
    !optimizationsFetched ||
    !blueprintsFetched
  ) {
    return undefined;
  }

  return stats;
}
