import {
  MANAGEMENT_PERMISSION,
  WORKSPACE_OWNER_VALUE,
} from "@/plugins/comet/constants/permissions";
import {
  ManagementPermissionsNames,
  UserPermission,
} from "@/plugins/comet/types";

export const isUserPermissionValid = (permissionValue?: string) =>
  permissionValue === WORKSPACE_OWNER_VALUE;

export const getPermissionByType = (
  userPermissions: UserPermission[] = [],
  type: ManagementPermissionsNames,
) => {
  const isTypeValid = Object.values(ManagementPermissionsNames).includes(type);
  if (!isTypeValid) return null;

  return (
    userPermissions?.find(({ permissionName }) => permissionName === type) ??
    null
  );
};

export const updatePermissionByType = (
  userPermissions: UserPermission[] = [],
  type: ManagementPermissionsNames,
  value: "true" | "false",
) =>
  userPermissions?.map((permission) => {
    if (permission?.permissionName === type) {
      return {
        ...permission,
        permissionValue: value,
      };
    }

    return permission;
  });

export const getKeyForChangingRole = (
  currentUserName: string,
  userName: string,
) => {
  return currentUserName === userName
    ? MANAGEMENT_PERMISSION.CHANGE_WORKSPACE_ROLE_FOR_YOURSELF
    : MANAGEMENT_PERMISSION.CHANGE_WORKSPACE_ROLE;
};

export const getPermissionStatusByKey = ({
  permissionKey,
  inviteUsersStatus,
  onlyAdminsCanInviteOutsideOrganizationStatus,
  managementStatus,
}: {
  permissionKey: MANAGEMENT_PERMISSION;
  inviteUsersStatus: boolean;
  onlyAdminsCanInviteOutsideOrganizationStatus: boolean;
  managementStatus: boolean;
}) => {
  if (permissionKey === MANAGEMENT_PERMISSION.INVITE_USERS_FROM_ORGANIZATION)
    return inviteUsersStatus;

  if (
    permissionKey === MANAGEMENT_PERMISSION.INVITE_USERS_OUT_OF_ORGANIZATION
  ) {
    if (onlyAdminsCanInviteOutsideOrganizationStatus) return false;
    return inviteUsersStatus;
  }

  if (permissionKey === MANAGEMENT_PERMISSION.CHANGE_WORKSPACE_ROLE) {
    return managementStatus;
  }

  console.error(`${permissionKey} is not considered for the system`);
  return false;
};
