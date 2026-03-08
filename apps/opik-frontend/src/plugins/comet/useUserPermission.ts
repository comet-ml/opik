import { useCallback, useMemo } from "react";
import find from "lodash/find";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import useCurrentOrganization from "./useCurrentOrganization";
import useUserPermissions from "./useUserPermissions";
import { ManagementPermissionsNames, ORGANIZATION_ROLE_TYPE } from "./types";
import { getUserPermissionValue } from "@/plugins/comet/lib/permissions";

const useUserPermission = (config?: { enabled?: boolean }) => {
  const configEnabled = config?.enabled ?? true;

  const currentOrganization = useCurrentOrganization();

  const userName = useLoggedInUserName();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const isAdmin = currentOrganization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const isEnabled = configEnabled && !isAdmin;
  const { data: userPermissions, isPending } = useUserPermissions(
    {
      organizationId: currentOrganization?.id || "",
      userName: userName || "",
    },
    {
      refetchOnMount: true,
      // there is no need in permissions, if a user is admin
      enabled: isEnabled,
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
      !!getUserPermissionValue(
        workspacePermissions,
        ManagementPermissionsNames.MANAGEMENT,
      ),
    [workspacePermissions, isAdmin],
  );

  const canInviteMembers = useMemo(
    () =>
      isWorkspaceOwner ||
      !!getUserPermissionValue(
        workspacePermissions,
        ManagementPermissionsNames.INVITE_USERS,
      ),
    [workspacePermissions, isWorkspaceOwner],
  );

  const checkNullablePermission = useCallback(
    (permissionName: ManagementPermissionsNames, defaultToFalse?: boolean) => {
      if (isWorkspaceOwner) return true;

      const permissionValue = getUserPermissionValue(
        workspacePermissions,
        permissionName,
      );

      if (defaultToFalse) {
        return permissionValue === true;
      }

      // should default to true if the permission is not found
      return permissionValue !== false;
    },
    [workspacePermissions, isWorkspaceOwner],
  );

  const canViewDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_VIEW),
    [checkNullablePermission],
  );

  const canViewExperiments = useMemo(
    () =>
      canViewDatasets &&
      checkNullablePermission(ManagementPermissionsNames.EXPERIMENT_VIEW),
    [canViewDatasets, checkNullablePermission],
  );

  const canViewDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_VIEW),
    [checkNullablePermission],
  );

  const canDeleteProjects = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROJECT_DELETE),
    [checkNullablePermission],
  );

  const canDeleteAnnotationQueues = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.ANNOTATION_QUEUE_DELETE,
      ),
    [checkNullablePermission],
  );

  const canDeleteTraces = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.TRACE_DELETE),
    [checkNullablePermission],
  );

  const canDeletePrompts = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROMPT_DELETE),
    [checkNullablePermission],
  );

  const canDeleteDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_DELETE),
    [checkNullablePermission],
  );

  const canDeleteOptimizationRuns = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.OPTIMIZATION_RUN_DELETE,
      ),
    [checkNullablePermission],
  );

  const canUpdateUserRole = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.USER_ROLE_UPDATE,
        true,
      ),
    [checkNullablePermission],
  );

  const canUpdateAIProviders = useMemo(
    () =>
      checkNullablePermission(ManagementPermissionsNames.AI_PROVIDER_UPDATE),
    [checkNullablePermission],
  );

  return {
    canInviteMembers,
    isWorkspaceOwner,
    canViewExperiments,
    canViewDashboards,
    canViewDatasets,
    canDeleteProjects,
    canDeleteAnnotationQueues,
    canDeleteTraces,
    canDeletePrompts,
    canDeleteDatasets,
    canDeleteOptimizationRuns,
    canUpdateUserRole,
    canUpdateAIProviders,
    isPending: isEnabled && isPending,
  };
};

export default useUserPermission;
