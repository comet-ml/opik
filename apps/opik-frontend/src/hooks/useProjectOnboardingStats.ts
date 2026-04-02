import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import api, {
  PROJECTS_REST_ENDPOINT,
  OPTIMIZATIONS_KEY,
  AGENT_CONFIGS_KEY,
  PROJECT_STATISTICS_KEY,
} from "@/api/api";

/**
 * Lightweight counts used by the assistant sidebar to understand
 * where the user is in their onboarding journey. Each query fetches
 * size=1 and reads the `total` field, minimizing data transfer.
 */
export interface ProjectOnboardingStats {
  traceCount: number;
  experimentCount: number;
  optimizationCount: number;
  blueprintVersionCount: number;
}

const EMPTY_STATS: ProjectOnboardingStats = {
  traceCount: 0,
  experimentCount: 0,
  optimizationCount: 0,
  blueprintVersionCount: 0,
};

export default function useProjectOnboardingStats(
  projectId: string | null | undefined,
): ProjectOnboardingStats {
  const enabled = !!projectId;

  const { data: statsData } = useQuery({
    queryKey: [PROJECT_STATISTICS_KEY, "onboarding-trace-count", projectId],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(`${PROJECTS_REST_ENDPOINT}stats`, {
        signal,
        params: { size: 1, page: 1, project_id: projectId },
      });
      const match = (data.content ?? []).find(
        (s: { project_id?: string }) => s.project_id === projectId,
      );
      return (match?.trace_count as number) ?? 0;
    },
    enabled,
    staleTime: 30_000,
  });

  const { data: experimentTotal } = useQuery({
    queryKey: ["experiments", "onboarding-count", projectId],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(
        `${PROJECTS_REST_ENDPOINT}${projectId}/experiments`,
        { signal, params: { size: 1, page: 1 } },
      );
      return (data.total as number) ?? 0;
    },
    enabled,
    staleTime: 30_000,
  });

  const { data: optimizationTotal } = useQuery({
    queryKey: [OPTIMIZATIONS_KEY, "onboarding-count", projectId],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(
        `${PROJECTS_REST_ENDPOINT}${projectId}/optimizations`,
        { signal, params: { size: 1, page: 1 } },
      );
      return (data.total as number) ?? 0;
    },
    enabled,
    staleTime: 30_000,
  });

  const { data: blueprintTotal } = useQuery({
    queryKey: [AGENT_CONFIGS_KEY, "onboarding-count", projectId],
    queryFn: async ({ signal }) => {
      const { data } = await api.get(
        `/v1/private/agent-configs/blueprints/history/projects/${projectId}`,
        { signal, params: { size: 1, page: 1 } },
      );
      return (data.total as number) ?? 0;
    },
    enabled,
    staleTime: 30_000,
  });

  return useMemo<ProjectOnboardingStats>(() => {
    if (!enabled) return EMPTY_STATS;
    return {
      traceCount: statsData ?? 0,
      experimentCount: experimentTotal ?? 0,
      optimizationCount: optimizationTotal ?? 0,
      blueprintVersionCount: blueprintTotal ?? 0,
    };
  }, [enabled, statsData, experimentTotal, optimizationTotal, blueprintTotal]);
}
