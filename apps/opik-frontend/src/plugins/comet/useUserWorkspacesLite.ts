import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { Workspace } from "./types";

const getUserWorkspacesLite = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<Workspace[]>(`/workspaces/lite`, { signal });
  return data;
};

export default function useUserWorkspacesLite(
  options?: QueryConfig<Workspace[]>,
) {
  return useQuery({
    queryKey: ["user-workspaces-lite", { enabled: true }],
    queryFn: getUserWorkspacesLite,
    // Any failure (404, 405, 5xx, network, etc.) falls back to legacy
    // /api/workspaces hooks via the orchestrator in useAllWorkspaces.
    // Disable retries so the fallback kicks in immediately instead of
    // burning ~10s on exponential-backoff retries.
    retry: false,
    staleTime: Infinity,
    ...options,
    enabled: Boolean(options?.enabled),
  });
}
