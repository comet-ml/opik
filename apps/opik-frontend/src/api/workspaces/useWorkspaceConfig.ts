import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  WORKSPACE_CONFIG_REST_ENDPOINT,
  WORKSPACE_CONFIG_KEY,
  QueryConfig,
} from "@/api/api";
import { WorkspaceConfig } from "@/types/workspaces";

const DEFAULT_CONFIG: WorkspaceConfig = {
  timeout_to_mark_thread_as_inactive: null,
  truncation_on_tables: null,
  color_map: null,
};

type UseWorkspaceConfigResponse = WorkspaceConfig;
type UseWorkspaceConfigParams = {
  workspaceName: string;
};

const getWorkspaceConfig = async ({
  signal,
}: QueryFunctionContext): Promise<WorkspaceConfig> => {
  // 404 means the workspace has no configuration yet, which is valid —
  // accept it so react-query treats it as success rather than error
  const { data, status } = await api.get(WORKSPACE_CONFIG_REST_ENDPOINT, {
    signal,
    validateStatus: (s) => (s >= 200 && s < 300) || s === 404,
  });

  return status === 404 ? DEFAULT_CONFIG : data;
};

export default function useWorkspaceConfig(
  params: UseWorkspaceConfigParams,
  options?: QueryConfig<UseWorkspaceConfigResponse>,
) {
  return useQuery({
    queryKey: [WORKSPACE_CONFIG_KEY, params],
    queryFn: (context) => getWorkspaceConfig(context),
    ...options,
  });
}
