import { useCallback, useMemo } from "react";
import find from "lodash/find";
import useAppStore, { useLoggedInUserName } from "@/store/AppStore";
import { getUserPermissionValue } from "@/plugins/comet/lib/permissions";
import useCurrentOrganization from "./useCurrentOrganization";
import useUserPermissions from "./useUserPermissions";
import { ManagementPermissionsNames, ORGANIZATION_ROLE_TYPE } from "./types";

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
    (permissionName: ManagementPermissionsNames) => {
      if (isWorkspaceOwner) return true;

      const permissionValue = getUserPermissionValue(
        workspacePermissions,
        permissionName,
      );

      // should default to true if the permission is not found
      return permissionValue !== false;
    },
    [workspacePermissions, isWorkspaceOwner],
  );

  const canViewDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_VIEW),
    [checkNullablePermission],
  );

  const canCreateDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_CREATE),
    [checkNullablePermission],
  );

  const canEditDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_EDIT),
    [checkNullablePermission],
  );

  const canDeleteDatasets = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DATASET_DELETE),
    [checkNullablePermission],
  );

  const canViewExperiments = useMemo(
    () =>
      canViewDatasets &&
      checkNullablePermission(ManagementPermissionsNames.EXPERIMENT_VIEW),
    [canViewDatasets, checkNullablePermission],
  );

  const canCreateExperiments = useMemo(
    () =>
      canViewExperiments &&
      checkNullablePermission(ManagementPermissionsNames.EXPERIMENT_CREATE),
    [canViewExperiments, checkNullablePermission],
  );

  const canViewDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_VIEW),
    [checkNullablePermission],
  );

  const canCreateDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_CREATE),
    [checkNullablePermission],
  );

  const canEditDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_EDIT),
    [checkNullablePermission],
  );

  const canDeleteDashboards = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.DASHBOARD_DELETE),
    [checkNullablePermission],
  );

  const canDeleteProjects = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROJECT_DELETE),
    [checkNullablePermission],
  );

  const canCreateAnnotationQueues = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.ANNOTATION_QUEUE_CREATE,
      ),
    [checkNullablePermission],
  );

  const canEditAnnotationQueues = useMemo(
    () =>
      checkNullablePermission(ManagementPermissionsNames.ANNOTATION_QUEUE_EDIT),
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

  const canCreatePrompts = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROMPT_CREATE),
    [checkNullablePermission],
  );

  const canDeletePrompts = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROMPT_DELETE),
    [checkNullablePermission],
  );

  const canDeleteOptimizationRuns = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.OPTIMIZATION_RUN_DELETE,
      ),
    [checkNullablePermission],
  );

  const canConfigureWorkspaceSettings = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.WORKSPACE_SETTINGS_CONFIGURE,
      ),
    [checkNullablePermission],
  );

  const canUpdateAIProviders = useMemo(
    () =>
      checkNullablePermission(ManagementPermissionsNames.AI_PROVIDER_UPDATE),
    [checkNullablePermission],
  );

  const canCreateProjects = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PROJECT_CREATE),
    [checkNullablePermission],
  );

  const canUpdateOnlineEvaluationRules = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.ONLINE_EVALUATION_RULE_UPDATE,
      ),
    [checkNullablePermission],
  );

  const canUpdateAlerts = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.ALERT_UPDATE),
    [checkNullablePermission],
  );

  const canTagTrace = useMemo(
    () =>
      checkNullablePermission(ManagementPermissionsNames.TRACE_SPAN_THREAD_LOG),
    [checkNullablePermission],
  );

  const canAnnotateTraceSpanThread = useMemo(
    () =>
      checkNullablePermission(
        ManagementPermissionsNames.TRACE_SPAN_THREAD_ANNOTATE,
      ),
    [checkNullablePermission],
  );

  const canUsePlayground = useMemo(
    () => checkNullablePermission(ManagementPermissionsNames.PLAYGROUND_USE),
    [checkNullablePermission],
  );

  const canViewOptimizationRuns = useMemo(
    () =>
      checkNullablePermission(ManagementPermissionsNames.OPTIMIZATION_RUN_VIEW),
    [checkNullablePermission],
  );

  const canUseOptimizationStudio = useMemo(
    () =>
      canViewOptimizationRuns &&
      checkNullablePermission(
        ManagementPermissionsNames.OPTIMIZATION_STUDIO_USE,
      ),
    [canViewOptimizationRuns, checkNullablePermission],
  );

  return {
    canInviteMembers,
    isWorkspaceOwner,
    canViewExperiments,
    canCreateExperiments,
    canViewDashboards,
    canCreateDashboards,
    canEditDashboards,
    canDeleteDashboards,
    canViewDatasets,
    canCreateDatasets,
    canEditDatasets,
    canDeleteDatasets,
    canCreateProjects,
    canDeleteProjects,
    canCreateAnnotationQueues,
    canEditAnnotationQueues,
    canDeleteAnnotationQueues,
    canDeleteTraces,
    canCreatePrompts,
    canDeletePrompts,
    canDeleteOptimizationRuns,
    canConfigureWorkspaceSettings,
    canUpdateAIProviders,
    canUpdateOnlineEvaluationRules,
    canUpdateAlerts,
    canAnnotateTraceSpanThread,
    canTagTrace,
    canUsePlayground,
    canUseOptimizationStudio,
    canViewOptimizationRuns,
    isPending: isEnabled && isPending,
  };
};

export default useUserPermission;
