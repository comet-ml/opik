import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "../api";
import { UserPermission } from "../types";

interface WorkspaceUserPermissions {
  userName: string;
  permissions: UserPermission[];
}

export interface WorkspaceUsersPermissionsResponse {
  usersPermissions: WorkspaceUserPermissions[];
}

interface UseWorkspacePermissionsParams {
  workspaceId: string;
}

const getWorkspaceUsersPermissions = async (
  { signal }: QueryFunctionContext,
  workspaceId: string,
) => {
  const { data } = await api.get<WorkspaceUsersPermissionsResponse>(
    `/permissions/workspace/${workspaceId}`,
    {
      signal,
    },
  );

  return data?.usersPermissions;
};

const useWorkspaceUsersPermissions = (
  { workspaceId }: UseWorkspacePermissionsParams,
  options?: QueryConfig<WorkspaceUserPermissions[]>,
) => {
  return useQuery({
    queryKey: ["workspace-permissions", { workspaceId }],
    queryFn: (context) => getWorkspaceUsersPermissions(context, workspaceId),
    ...options,
  });
};

export default useWorkspaceUsersPermissions;
