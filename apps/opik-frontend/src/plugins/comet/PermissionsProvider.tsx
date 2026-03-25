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
    canCreateProjects,
    canDeleteProjects,
    canCreateAnnotationQueues,
    canDeleteAnnotationQueues,
    canDeleteTraces,
    canDeletePrompts,
    canEditDatasets,
    canDeleteDatasets,
    canDeleteOptimizationRuns,
    canConfigureWorkspaceSettings,
    canUpdateAIProviders,
    canWriteComments,
    canUpdateOnlineEvaluationRules,
    canUpdateAlerts,
    canAnnotateTraceSpanThread,
    canTagTrace,
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
        canCreateProjects,
        canDeleteProjects,
        canCreateAnnotationQueues,
        canDeleteAnnotationQueues,
        canDeleteTraces,
        canDeletePrompts,
        canEditDatasets,
        canDeleteDatasets,
        canDeleteOptimizationRuns,
        canConfigureWorkspaceSettings,
        canUpdateAIProviders,
        canWriteComments,
        canUpdateOnlineEvaluationRules,
        canUpdateAlerts,
        canAnnotateTraceSpanThread,
        canTagTrace,
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
      canCreateProjects,
      canDeleteProjects,
      canCreateAnnotationQueues,
      canDeleteAnnotationQueues,
      canDeleteTraces,
      canDeletePrompts,
      canEditDatasets,
      canDeleteDatasets,
      canDeleteOptimizationRuns,
      canConfigureWorkspaceSettings,
      canUpdateAIProviders,
      canWriteComments,
      canUpdateOnlineEvaluationRules,
      canUpdateAlerts,
      canAnnotateTraceSpanThread,
      canTagTrace,
      isPending,
    ],
  );

  return (
    <BasePermissionsProvider value={value}>{children}</BasePermissionsProvider>
  );
};

export default PermissionsProvider;
