import { useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceVersion, useActiveWorkspaceName } from "@/store/AppStore";
import { DEFAULT_WORKSPACE_VERSION } from "@/lib/workspaceVersion";

type WorkspaceVersionResponse = {
  opik_version: "version_1" | "version_2";
};

const VERSION_MAP: Record<
  WorkspaceVersionResponse["opik_version"],
  WorkspaceVersion
> = {
  version_1: "v1",
  version_2: "v2",
};

export async function fetchWorkspaceVersion(opts?: {
  workspaceName?: string;
  signal?: AbortSignal;
}): Promise<WorkspaceVersion> {
  try {
    const { workspaceName, signal } = opts ?? {};
    const config = {
      ...(signal && { signal }),
      ...(workspaceName && {
        headers: { "Comet-Workspace": workspaceName },
      }),
    };
    const { data } = await api.get<WorkspaceVersionResponse>(
      WORKSPACES_REST_ENDPOINT + "versions",
      config,
    );
    return VERSION_MAP[data.opik_version] ?? DEFAULT_WORKSPACE_VERSION;
  } catch {
    return DEFAULT_WORKSPACE_VERSION;
  }
}

export default function useWorkspaceVersionQuery(
  options?: QueryConfig<WorkspaceVersion>,
) {
  const workspaceName = useActiveWorkspaceName();
  return useQuery({
    queryKey: ["workspace-version", { workspaceName }],
    queryFn: ({ signal }) => fetchWorkspaceVersion({ workspaceName, signal }),
    staleTime: 5 * 60 * 1000,
    ...options,
    enabled: !!workspaceName && (options?.enabled ?? true),
  });
}
