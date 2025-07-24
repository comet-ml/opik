import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  WORKSPACE_CONFIG_REST_ENDPOINT,
  WORKSPACE_CONFIG_KEY,
  QueryConfig,
} from "@/api/api";
import { WorkspaceConfig } from "@/types/workspaces";

type UseWorkspaceConfigResponse = WorkspaceConfig;
type UseWorkspaceConfigParams = {
  workspaceName: string;
};

const getWorkspaceConfig = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get(WORKSPACE_CONFIG_REST_ENDPOINT, {
    signal,
  });

  return data;
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
