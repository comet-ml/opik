import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
import { WorkspaceRole } from "../types";

interface WorkspaceRolesResponse {
  roles: WorkspaceRole[];
}

interface UseWorkspaceRolesParams {
  organizationId: string;
}

const getWorkspaceRoles = async (
  { signal }: QueryFunctionContext,
  organizationId: string,
): Promise<WorkspaceRole[]> => {
  const { data } = await api.get<WorkspaceRolesResponse>("/workspace-roles", {
    params: {
      organizationId,
    },
    signal,
  });

  return data?.roles || [];
};

const useWorkspaceRoles = (
  { organizationId }: UseWorkspaceRolesParams,
  options?: QueryConfig<WorkspaceRole[]>,
) => {
  return useQuery({
    queryKey: ["workspace-roles", { organizationId }],
    queryFn: (context) => getWorkspaceRoles(context, organizationId),
    ...options,
  });
};

export default useWorkspaceRoles;
