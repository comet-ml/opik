import { useCallback, useMemo } from "react";
import find from "lodash/find";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import useCurrentOrganization from "./useCurrentOrganization";
import useUserPermissions from "./useUserPermissions";
import { ManagementPermissionsNames, ORGANIZATION_ROLE_TYPE } from "./types";
import { getUserPermissionValue } from "@/plugins/comet/lib/permissions";

const useUserPermission = (config?: { enabled?: boolean }) => {
  const configEnabled = config?.enabled ?? true;

  const currentOrganization = useCurrentOrganization();

  const userName = useLoggedInUserName();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const isEnabled = configEnabled && !isAdmin;
  const { data: userPermissions, isPending } = useUserPermissions(
    {
      organizationId: currentOrganization?.id || "",
      userName: userName || "",
    },
    {
      refetchOnMount: true,
      // there is no need in permissions, if a user is admin
      enabled: isEnabled,
    },
  );

  const workspacePermissions = useMemo(
    () =>
      find(
        userPermissions || [],
        (permission) => permission?.workspaceName === workspaceName,
      )?.permissions || [],
    [userPermissions, workspaceName],
  );

  const isWorkspaceOwner = useMemo(
    () =>
      isAdmin ||
      !!getUserPermissionValue(
        workspacePermissions,
        ManagementPermissionsNames.MANAGEMENT,
      ),
    [workspacePermissions, isAdmin],
  );

  const canInviteMembers = useMemo(
    () =>
      isWorkspaceOwner ||
      !!getUserPermissionValue(
        workspacePermissions,
        ManagementPermissionsNames.INVITE_USERS,
      ),
    [workspacePermissions, isWorkspaceOwner],
  );

  const checkNullablePermission = useCallback(
    (permissionName: ManagementPermissionsNames) => {
      if (isWorkspaceOwner) return true;

      const permissionValue = getUserPermissionValue(
        workspacePermissions,
        permissionName,
      );

      // should default to true if the permission is not found
      return permissionValue !== false;
    },
    [workspacePermissions, isWorkspaceOwner],
  );

  const canViewExperiments = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.EXPERIMENT_VIEW),
    [checkNullablePermission],
  );

  const canViewDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_VIEW),
    [checkNullablePermission],
  );

  return {
    canInviteMembers,
    isWorkspaceOwner,
    canViewExperiments,
    canViewDashboards,
    isPending: isEnabled && isPending,
  };
};

export default useUserPermission;
