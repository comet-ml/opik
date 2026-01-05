import { useCallback, useMemo } from "react";
import find from "lodash/find";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import useCurrentOrganization from "./useCurrentOrganization";
import useUserPermissions from "./useUserPermissions";
import { MANAGEMENT_PERMISSION } from "@/plugins/comet/constants/permissions";
import { ManagementPermissionsNames, ORGANIZATION_ROLE_TYPE } from "./types";
import {
  getPermissionByType,
  getPermissionStatusByKey,
  isUserPermissionValid,
} from "@/plugins/comet/lib/permissions";

const useUserPermission = (config?: { enabled?: boolean }) => {
  const configEnabled = config?.enabled ?? true;

  const currentOrganization = useCurrentOrganization();

  const userName = useLoggedInUserName();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const { data: userPermissions } = useUserPermissions(
    {
      organizationId: currentOrganization?.id || "",
      userName: userName || "",
    },
    {
      refetchOnMount: true,
      // there is no need in permissions, if a user is admin
      enabled: configEnabled && !isAdmin,
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
      isUserPermissionValid(
        getPermissionByType(
          workspacePermissions,
          ManagementPermissionsNames.MANAGEMENT,
        )?.permissionValue,
      ),
    [workspacePermissions, isAdmin],
  );

  const canInviteMembers = useMemo(
    () =>
      isWorkspaceOwner ||
      isUserPermissionValid(
        getPermissionByType(
          workspacePermissions,
          ManagementPermissionsNames.INVITE_USERS,
        )?.permissionValue,
      ),
    [workspacePermissions, isWorkspaceOwner],
  );

  const getPermissionStatus = useCallback(
    (permissionKey: MANAGEMENT_PERMISSION) => {
      if (!currentOrganization) return false;

      if (
        permissionKey ===
        MANAGEMENT_PERMISSION.CHANGE_WORKSPACE_ROLE_FOR_YOURSELF
      ) {
        return false;
      }

      if (isAdmin) return true;

      if (!userPermissions?.length || !workspacePermissions?.length) {
        return false;
      }

      return getPermissionStatusByKey({
        permissionKey,
        inviteUsersStatus: canInviteMembers,
        onlyAdminsCanInviteOutsideOrganizationStatus:
          currentOrganization?.onlyAdminsInviteByEmail,
        managementStatus: isWorkspaceOwner,
      });
    },
    [
      canInviteMembers,
      isWorkspaceOwner,
      isAdmin,
      currentOrganization,
      userPermissions?.length,
      workspacePermissions?.length,
    ],
  );

  return { canInviteMembers, isWorkspaceOwner, getPermissionStatus };
};

export default useUserPermission;
