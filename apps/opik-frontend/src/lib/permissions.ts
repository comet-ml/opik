import {
  MANAGEMENT_PERMISSIONS,
  WORKSPACE_OWNER_VALUE,
} from "@/constants/permissions";
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
  const isArray = Array.isArray(userPermissions);
  const isTypeValid = Object.values(ManagementPermissionsNames).includes(type);

  if (!isArray || !isTypeValid) return null;

  const permission = userPermissions?.filter(
    ({ permissionName }) => permissionName === type,
  );

  return permission?.[0] || null;
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
    ? MANAGEMENT_PERMISSIONS.CHANGE_WORKSPACE_ROLE_FOR_YOURSELF
    : MANAGEMENT_PERMISSIONS.CHANGE_WORKSPACE_ROLE;
};

export const getPermissionStatusByKey = ({
  permissionKey,
  inviteUsersStatus,
  onlyAdminsCanInviteOutsideOrganizationStatus,
}: {
  permissionKey: (typeof MANAGEMENT_PERMISSIONS)[keyof typeof MANAGEMENT_PERMISSIONS];
  inviteUsersStatus: boolean;
  onlyAdminsCanInviteOutsideOrganizationStatus: boolean;
}) => {
  if (permissionKey === MANAGEMENT_PERMISSIONS.INVITE_USERS_FROM_ORGANIZATION)
    return inviteUsersStatus;

  if (
    permissionKey === MANAGEMENT_PERMISSIONS.INVITE_USERS_OUT_OF_ORGANIZATION
  ) {
    if (onlyAdminsCanInviteOutsideOrganizationStatus) return false;
    return inviteUsersStatus;
  }

  console.error(`${permissionKey} is not considered for the system`);
  return false;
};
