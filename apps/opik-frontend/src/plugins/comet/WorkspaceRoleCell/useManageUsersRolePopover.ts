import { useCallback, useMemo } from "react";
import {
  getPermissionByType,
  isUserPermissionValid,
  updatePermissionByType,
} from "@/plugins/comet/lib/permissions";
import {
  ManagementPermissionsNames,
  UserPermission,
} from "@/plugins/comet/types";
import {
  WORKSPACE_MEMBER_VALUE,
  WORKSPACE_OWNER_VALUE,
} from "@/plugins/comet/constants/permissions";
import { useLoggedInUserName } from "@/store/AppStore";

export interface WorkspaceMemberPermission {
  key: ManagementPermissionsNames;
  label: string;
  text: string;
}

const useManageUsersRolePopover = (
  permissions: UserPermission[] = [],
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

  const onRadioChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const updatedPermissions = updatePermissionByType(
        permissions,
        ManagementPermissionsNames.MANAGEMENT,
        e?.target?.value as "true" | "false",
      );
      setPermissions(updatedPermissions);
    },
    [permissions, setPermissions],
  );

  const onCheckboxChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>, { value }: { value: string }) => {
      const updatedPermissions = updatePermissionByType(
        permissions,
        value as ManagementPermissionsNames,
        e?.target?.checked ? "true" : "false",
      );
      setPermissions(updatedPermissions);
    },
    [permissions, setPermissions],
  );

  return useMemo(() => {
    const checkboxOptions = [
      {
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
      },
    ];

    return {
      controlType: "radio" as const,
      controlValue: wsManagementPermissionValue,
      onChange: onRadioChange,
      options: [
        {
          key: `${ManagementPermissionsNames.MANAGEMENT}-${WORKSPACE_OWNER_VALUE}`,
          value: WORKSPACE_OWNER_VALUE,
          label: "Workspace Owner",
          text: "Full permissions to all workspace resources",
          controlType: "radio" as const,
        },
        {
          key: `${ManagementPermissionsNames.MANAGEMENT}-${WORKSPACE_MEMBER_VALUE}`,
          value: WORKSPACE_MEMBER_VALUE,
          label: "Workspace Member",
          text: "Limited permissions. You can give customized ones",
          controlType: "radio" as const,
          disabled: isDisabled,
          tooltip: isDisabled
            ? {
                arrow: true,
                title: ifChangeMadeForCurrentUser
                  ? "You can't update your own role"
                  : "You can't change the role, since this user is an organization admin",
                placement: "left" as const,
              }
            : null,
          list:
            wsManagementPermissionValue === WORKSPACE_MEMBER_VALUE
              ? {
                  options: checkboxOptions,
                  controlType: "checkbox" as const,
                  onChange: onCheckboxChange,
                }
              : null,
        },
      ],
    };
  }, [
    permissions,
    wsManagementPermissionValue,
    ifChangeMadeForCurrentUser,
    isDisabled,
    onRadioChange,
    onCheckboxChange,
  ]);
};

export default useManageUsersRolePopover;
