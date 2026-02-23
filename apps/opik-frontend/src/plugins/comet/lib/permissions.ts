import { WORKSPACE_OWNER_VALUE } from "@/plugins/comet/constants/permissions";
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
