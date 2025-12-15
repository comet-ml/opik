import { useMemo } from "react";
import {
  getPermissionByType,
  isUserPermissionValid,
  updatePermissionByType,
} from "@/lib/permissions";
import {
  ManagementPermissionsNames,
  UserPermission,
} from "@/plugins/comet/types";
import {
  CANNOT_CHANGE_MY_ROLE_IN_WS_TOOLTIP,
  CANNOT_CHANGE_ORG_ADMIN_ROLE_IN_WS_TOOLTIP,
  WORKSPACE_MEMBER_VALUE,
  WORKSPACE_OWNER_VALUE,
} from "@/constants/permissions";
import { useLoggedInUserName } from "@/store/AppStore";

export interface WorkspaceMemberPermission {
  key: ManagementPermissionsNames;
  label: string;
  text: string;
}

const useManageUsersRolePopover = (
  permissions: UserPermission[],
  username: string,
  ifChangeWsRoleDisabled: boolean,
  ifUserAdmin: boolean,
  setPermissions: (permissions: UserPermission[]) => void,
) => {
  const currentUserName = useLoggedInUserName();
  const wsManagementPermissionValue = getPermissionByType(
    permissions,
    ManagementPermissionsNames.MANAGEMENT,
  )?.permissionValue;
  const ifChangeMadeForCurrentUser = currentUserName === username;
  const isDisabled = ifChangeWsRoleDisabled || ifUserAdmin;

  return useMemo(() => {
    const checkboxOption = {
      key: ManagementPermissionsNames.INVITE_USERS,
      label: "Invite Users (IU)",
      text: "Invite users to workspace",
      value: ManagementPermissionsNames.INVITE_USERS,
      checked: isUserPermissionValid(
        getPermissionByType(
          permissions,
          ManagementPermissionsNames.INVITE_USERS,
        )?.permissionValue,
      ),
      disabled: false,
      color: "primary" as const,
      controlType: "checkbox" as const,
    };

    return {
      controlType: "radio" as const,
      controlValue: wsManagementPermissionValue,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => {
        const updatedPermissions = updatePermissionByType(
          permissions,
          ManagementPermissionsNames.MANAGEMENT,
          e?.target?.value as "true" | "false",
        );
        setPermissions(updatedPermissions);
      },
      options: [
        {
          key: `${ManagementPermissionsNames.MANAGEMENT}-owner`,
          value: WORKSPACE_OWNER_VALUE,
          label: "Workspace Owner",
          text: "Full permissions to all workspace resources",
          controlType: "radio" as const,
        },
        {
          key: `${ManagementPermissionsNames.MANAGEMENT}-member`,
          value: WORKSPACE_MEMBER_VALUE,
          label: "Workspace Member",
          text: "Limited permissions. You can give customized ones",
          controlType: "radio" as const,
          disabled: isDisabled,
          tooltip: isDisabled
            ? {
                arrow: true,
                title: ifChangeMadeForCurrentUser
                  ? CANNOT_CHANGE_MY_ROLE_IN_WS_TOOLTIP
                  : CANNOT_CHANGE_ORG_ADMIN_ROLE_IN_WS_TOOLTIP,
                placement: "left" as const,
              }
            : null,
          list:
            wsManagementPermissionValue === WORKSPACE_MEMBER_VALUE
              ? {
                  options: [checkboxOption],
                  controlType: "checkbox" as const,
                  onChange: (
                    e: React.ChangeEvent<HTMLInputElement>,
                    { value }: { value: string },
                  ) => {
                    const updatedPermissions = updatePermissionByType(
                      permissions,
                      value as ManagementPermissionsNames,
                      e?.target?.checked ? "true" : "false",
                    );
                    setPermissions(updatedPermissions);
                  },
                }
              : null,
        },
      ],
    };
  }, [
    permissions,
    wsManagementPermissionValue,
    ifChangeMadeForCurrentUser,
    setPermissions,
    isDisabled,
  ]);
};

export default useManageUsersRolePopover;
