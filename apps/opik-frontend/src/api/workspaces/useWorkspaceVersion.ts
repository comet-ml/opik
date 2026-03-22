import { useQuery } from "@tanstack/react-query";
import api, { QueryConfig, WORKSPACES_REST_ENDPOINT } from "@/api/api";
import { WorkspaceVersion, useActiveWorkspaceName } from "@/store/AppStore";
import { DEFAULT_WORKSPACE_VERSION } from "@/lib/workspaceVersion";

type WorkspaceVersionResponse = {
  opikVersion: "opik1" | "opik2";
};

const VERSION_MAP: Record<
  WorkspaceVersionResponse["opikVersion"],
  WorkspaceVersion
> = {
  opik1: "v1",
  opik2: "v2",
};

export async function fetchWorkspaceVersion(opts?: {
  workspaceName?: string;
  signal?: AbortSignal;
}): Promise<WorkspaceVersion> {
  // TODO: remove early return when BE endpoint (OPIK-5171) is implemented
  return DEFAULT_WORKSPACE_VERSION;

  try {
    const { workspaceName, signal } = opts ?? {};
    const config = {
      ...(signal && { signal }),
      ...(workspaceName && {
        headers: { "Comet-Workspace": workspaceName },
      }),
    };
    const { data } = await api.get<WorkspaceVersionResponse>(
      WORKSPACES_REST_ENDPOINT + "version",
      config,
    );
    return VERSION_MAP[data.opikVersion] ?? DEFAULT_WORKSPACE_VERSION;
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
