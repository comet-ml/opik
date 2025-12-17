import { useCallback } from "react";
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

      if (!userPermissions?.length) return false;

      const workspacePermissions =
        find(
          userPermissions || [],
          (permission) => permission?.workspaceName === workspaceName,
        )?.permissions || [];

      if (!workspacePermissions?.length) return false;

      const inviteUsersStatus = isUserPermissionValid(
        getPermissionByType(
          workspacePermissions,
          ManagementPermissionsNames.INVITE_USERS,
        )?.permissionValue,
      );

      const managementStatus = isUserPermissionValid(
        getPermissionByType(
          workspacePermissions,
          ManagementPermissionsNames.MANAGEMENT,
        )?.permissionValue,
      );

      return getPermissionStatusByKey({
        permissionKey,
        inviteUsersStatus,
        onlyAdminsCanInviteOutsideOrganizationStatus:
          currentOrganization?.onlyAdminsInviteByEmail,
        managementStatus,
      });
    },
    [userPermissions, currentOrganization, isAdmin, workspaceName],
  );

  return { getPermissionStatus };
};

export default useUserPermission;
