import { useQuery } from "@tanstack/react-query";
import { QueryConfig } from "@/api/api";
import { WorkspaceVersion, useActiveWorkspaceName } from "@/store/AppStore";

// Opik V1 is deprecated and the backend /v1/private/workspaces/versions endpoint
// has been removed. Every workspace now resolves to V2, so this no longer hits the
// backend and always reports "v2".
export async function fetchWorkspaceVersion(): Promise<WorkspaceVersion> {
  return "v2";
}

export default function useWorkspaceVersionQuery(
  options?: QueryConfig<WorkspaceVersion>,
) {
  const workspaceName = useActiveWorkspaceName();
  return useQuery({
    queryKey: ["workspace-version", { workspaceName }],
    queryFn: () => fetchWorkspaceVersion(),
    staleTime: 5 * 60 * 1000,
    ...options,
    enabled: !!workspaceName && (options?.enabled ?? true),
  });
}
