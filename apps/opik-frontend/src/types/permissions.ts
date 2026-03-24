export interface Permissions {
  canViewExperiments: boolean;
  canCreateExperiments: boolean;
  canViewDashboards: boolean;
  canCreateDashboards: boolean;
  canEditDashboards: boolean;
  canDeleteDashboards: boolean;
  canViewDatasets: boolean;
  canEditDatasets: boolean;
  canDeleteDatasets: boolean;
  canDeleteProjects: boolean;
  canCreateAnnotationQueues: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
  canDeletePrompts: boolean;
  canDeleteOptimizationRuns: boolean;
  canConfigureWorkspaceSettings: boolean;
  canUpdateAIProviders: boolean;
  canCreateProjects: boolean;
  canWriteComments: boolean;
  canUpdateOnlineEvaluationRules: boolean;
  canUpdateAlerts: boolean;
  canAnnotateTraceSpanThread: boolean;
  canTagTrace: boolean;
}

export interface PermissionsContextValue {
  permissions: Permissions;
  isPending: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsContextValue = {
  permissions: {
    canViewExperiments: true,
    canCreateExperiments: true,
    canViewDashboards: true,
    canCreateDashboards: true,
    canEditDashboards: true,
    canDeleteDashboards: true,
    canViewDatasets: true,
    canEditDatasets: true,
    canDeleteDatasets: true,
    canDeleteProjects: true,
    canCreateAnnotationQueues: true,
    canDeleteAnnotationQueues: true,
    canDeleteTraces: true,
    canDeletePrompts: true,
    canDeleteOptimizationRuns: true,
    canConfigureWorkspaceSettings: true,
    canUpdateAIProviders: true,
    canCreateProjects: true,
    canWriteComments: true,
    canUpdateOnlineEvaluationRules: true,
    canUpdateAlerts: true,
    canAnnotateTraceSpanThread: true,
    canTagTrace: true,
  },
  isPending: false,
};
