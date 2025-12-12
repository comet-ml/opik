import {
  ManagementPermissionsNames,
  UserPermission,
} from "@/plugins/comet/types";

export const isUserPermissionValid = (permissionValue?: string) =>
  permissionValue === "true";

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
