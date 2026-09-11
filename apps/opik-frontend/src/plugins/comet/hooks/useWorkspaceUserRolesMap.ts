import { useMemo } from "react";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";
import useAllWorkspaceMembers from "@/plugins/comet/useWorkspaceMembers";
import useWorkspaceUsersPermissions from "@/plugins/comet/api/useWorkspaceUsersPermissions";
import useWorkspaceUsersRoles from "@/plugins/comet/api/useWorkspaceUsersRoles";
import {
  WORKSPACE_ROLE_TYPE,
  ManagementPermissionsNames,
} from "@/plugins/comet/types";
import { getUserPermissionValue } from "@/plugins/comet/lib/permissions";

interface UseWorkspaceUserRolesParams {
  workspaceId: string;
}

/**
 * Provides a role map for workspace users.
 * Automatically switches between the new permissions management system
 * and the legacy permissions system based on feature flag.
 *
 * @returns A map of user identifiers (userName or email) to role names
 */
export const useWorkspaceUserRolesMap = ({
  workspaceId,
}: UseWorkspaceUserRolesParams) => {
  const currentOrganization = useCurrentOrganization();
  const isPermissionsManagementEnabled =
    currentOrganization?.workspaceRolesEnabled ?? false;

  const { data: workspaceMembers = [] } = useAllWorkspaceMembers(
    { workspaceId },
    {
      enabled: Boolean(workspaceId),
    },
  );

  const { data: permissionsData = [] } = useWorkspaceUsersPermissions(
    { workspaceId },
    {
      enabled: Boolean(workspaceId) && !isPermissionsManagementEnabled,
    },
  );

  const { data: workspaceUsersRoles = [] } = useWorkspaceUsersRoles(
    { workspaceId },
    {
      enabled: Boolean(workspaceId) && isPermissionsManagementEnabled,
    },
  );

  const roleMapFromUsersRoles = useMemo(() => {
    const roleMap = new Map<string, string>();

    const userNameToRoleMap = new Map(
      workspaceUsersRoles.map((userRole) => [
        userRole.userName,
        userRole.roleName,
      ]),
    );

    userNameToRoleMap.forEach((roleName, userName) => {
      roleMap.set(userName.toLowerCase(), roleName);
    });

    workspaceMembers.forEach((member) => {
      if (member.email && member.userName) {
        const roleName = userNameToRoleMap.get(member.userName);
        if (roleName) {
          roleMap.set(member.email.toLowerCase(), roleName);
        }
      }
    });

    return roleMap;
  }, [workspaceUsersRoles, workspaceMembers]);

  const roleMapFromPermissions = useMemo(() => {
    const permissionsMap = new Map(
      permissionsData.map((permission) => [
        permission.userName,
        permission.permissions,
      ]),
    );

    const roleMap = new Map<string, WORKSPACE_ROLE_TYPE>();

    workspaceMembers.forEach((member) => {
      const userPermissions = member.userName
        ? permissionsMap.get(member.userName) || []
        : [];

      const isWorkspaceOwner = !!getUserPermissionValue(
        userPermissions,
        ManagementPermissionsNames.MANAGEMENT,
      );

      const role = isWorkspaceOwner
        ? WORKSPACE_ROLE_TYPE.owner
        : WORKSPACE_ROLE_TYPE.member;

      if (member.userName) {
        roleMap.set(member.userName.toLowerCase(), role);
      }
      if (member.email) {
        roleMap.set(member.email.toLowerCase(), role);
      }
    });

    return roleMap;
  }, [workspaceMembers, permissionsData]);

  const roleMap = isPermissionsManagementEnabled
    ? roleMapFromUsersRoles
    : roleMapFromPermissions;

  const getUserRole = (
    identifier: string,
  ): WORKSPACE_ROLE_TYPE | string | null => {
    return roleMap.get(identifier.toLowerCase()) || null;
  };

  return {
    getUserRole,
  };
};
