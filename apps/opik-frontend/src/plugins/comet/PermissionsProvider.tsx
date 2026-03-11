import React, { ReactNode, useMemo } from "react";
import { PermissionsProvider as BasePermissionsProvider } from "@/contexts/PermissionsContext";
import useUserPermission from "./useUserPermission";
import { PermissionsContextValue } from "@/types/permissions";

const PermissionsProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const {
    canViewExperiments,
    canViewDashboards,
    canViewDatasets,
    canDeleteProjects,
    canCreateAnnotationQueues,
    canDeleteAnnotationQueues,
    canDeleteTraces,
    canDeletePrompts,
    canDeleteDatasets,
    canDeleteOptimizationRuns,
    canUpdateUserRole,
    canConfigureWorkspaceSettings,
    canUpdateAIProviders,
    canCreateProjects,
    canWriteComments,
    isPending,
  } = useUserPermission();

  const value: PermissionsContextValue = useMemo(
    () => ({
      permissions: {
        canViewExperiments,
        canViewDashboards,
        canViewDatasets,
        canDeleteProjects,
        canCreateAnnotationQueues,
        canDeleteAnnotationQueues,
        canDeleteTraces,
        canDeletePrompts,
        canDeleteDatasets,
        canDeleteOptimizationRuns,
        canUpdateUserRole,
        canConfigureWorkspaceSettings,
        canUpdateAIProviders,
        canCreateProjects,
        canWriteComments,
      },
      isPending,
    }),
    [
      canViewExperiments,
      canViewDashboards,
      canViewDatasets,
      canDeleteProjects,
      canCreateAnnotationQueues,
      canDeleteAnnotationQueues,
      canDeleteTraces,
      canDeletePrompts,
      canDeleteDatasets,
      canDeleteOptimizationRuns,
      canUpdateUserRole,
      canConfigureWorkspaceSettings,
      canUpdateAIProviders,
      canCreateProjects,
      canWriteComments,
      isPending,
    ],
  );

  return (
    <BasePermissionsProvider value={value}>{children}</BasePermissionsProvider>
  );
};

export default PermissionsProvider;
