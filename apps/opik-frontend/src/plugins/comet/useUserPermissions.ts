import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig } from "./api";
import { UserPermission } from "./types";

type WorkspacePermission = {
  permissions: UserPermission[];
  workspaceName: string;
};

const getUserPermissions = async (
  { signal }: QueryFunctionContext,
  { organizationId, userName }: UseUserPermissionsParams,
) => {
  const { data } = await api.get<{
    userPermissions: WorkspacePermission[];
  }>(`/permissions/organization/${organizationId}/user/${userName}`, {
    signal,
  });

  return data.userPermissions;
};

type UseUserPermissionsParams = { organizationId: string; userName: string };

export default function useUserPermissions(
  { organizationId, userName }: UseUserPermissionsParams,
  options?: QueryConfig<WorkspacePermission[]>,
) {
  return useQuery({
    queryKey: ["user-permissions", { organizationId, userName }],
    queryFn: (context) =>
      getUserPermissions(context, { organizationId, userName }),
    ...options,
    enabled: Boolean(organizationId && userName) && (options?.enabled ?? true),
  });
}
