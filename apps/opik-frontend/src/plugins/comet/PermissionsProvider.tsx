import React, { ReactNode, useMemo } from "react";
import { PermissionsProvider as BasePermissionsProvider } from "@/contexts/PermissionsContext";
import useUserPermission from "./useUserPermission";
import { PermissionsContextValue } from "@/types/permissions";

const PermissionsProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const {
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
    canWriteComments,
    canUpdateOnlineEvaluationRules,
    canUpdateAlerts,
    canAnnotateTraceSpanThread,
    canTagTrace,
    canUsePlayground,
    isPending,
  } = useUserPermission();

  const value: PermissionsContextValue = useMemo(
    () => ({
      permissions: {
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
        canWriteComments,
        canUpdateOnlineEvaluationRules,
        canUpdateAlerts,
        canAnnotateTraceSpanThread,
        canTagTrace,
        canUsePlayground,
      },
      isPending,
    }),
    [
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
      canWriteComments,
      canUpdateOnlineEvaluationRules,
      canUpdateAlerts,
      canAnnotateTraceSpanThread,
      canTagTrace,
      canUsePlayground,
      isPending,
    ],
  );

  return (
    <BasePermissionsProvider value={value}>{children}</BasePermissionsProvider>
  );
};

export default PermissionsProvider;
