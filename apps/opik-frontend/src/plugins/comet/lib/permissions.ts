import {
  ManagementPermissionsNames,
  UserPermission,
} from "@/plugins/comet/types";

export const getUserPermissionValue = (
  userPermissions: UserPermission[] = [],
  type: ManagementPermissionsNames,
) => {
  const isTypeValid = Object.values(ManagementPermissionsNames).includes(type);
  if (!isTypeValid) return null;

  const permissionValue = userPermissions?.find(
    ({ permissionName }) => permissionName === type,
  )?.permissionValue;

  if (permissionValue === "true") return true;
  if (permissionValue === "false") return false;
  return null;
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
