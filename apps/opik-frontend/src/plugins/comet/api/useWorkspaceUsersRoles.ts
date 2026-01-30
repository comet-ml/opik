import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";

interface PermissionMismatch {
  message: string;
}

export interface WorkspaceUserRole {
  userName: string;
  roleId: string;
  roleName: string;
  permissionMismatch?: PermissionMismatch;
}

interface WorkspaceUsersRolesResponse {
  users: WorkspaceUserRole[];
}

interface UseWorkspaceUsersRolesParams {
  workspaceId: string;
}

const getWorkspaceUsersRoles = async (
  { signal }: QueryFunctionContext,
  workspaceId: string,
): Promise<WorkspaceUserRole[]> => {
  const { data } = await api.get<WorkspaceUsersRolesResponse>(
    "/workspace-roles/users",
    {
      params: {
        workspaceId,
      },
      signal,
    },
  );

  return data?.users || [];
};

const useWorkspaceUsersRoles = (
  { workspaceId }: UseWorkspaceUsersRolesParams,
  options?: QueryConfig<WorkspaceUserRole[]>,
) => {
  return useQuery({
    queryKey: ["workspace-users-roles", { workspaceId }],
    queryFn: (context) => getWorkspaceUsersRoles(context, workspaceId),
    ...options,
  });
};

export default useWorkspaceUsersRoles;
