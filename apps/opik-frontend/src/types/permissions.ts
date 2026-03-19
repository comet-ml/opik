export interface Permissions {
  canViewExperiments: boolean;
  canCreateExperiments: boolean;
  canViewDashboards: boolean;
  canViewDatasets: boolean;
  canDeleteProjects: boolean;
  canCreateAnnotationQueues: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
  canDeletePrompts: boolean;
  canDeleteDatasets: boolean;
  canDeleteOptimizationRuns: boolean;
  canUpdateUserRole: boolean;
  canConfigureWorkspaceSettings: boolean;
  canUpdateAIProviders: boolean;
  canCreateProjects: boolean;
  canWriteComments: boolean;
  canUpdateOnlineEvaluationRules: boolean;
  canUpdateAlerts: boolean;
  canAnnotateTraceSpanThread: boolean;
  canCreateDashboards: boolean;
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
    canViewDatasets: true,
    canDeleteProjects: true,
    canCreateAnnotationQueues: true,
    canDeleteAnnotationQueues: true,
    canDeleteTraces: true,
    canDeletePrompts: true,
    canDeleteDatasets: true,
    canDeleteOptimizationRuns: true,
    canUpdateUserRole: true,
    canConfigureWorkspaceSettings: true,
    canUpdateAIProviders: true,
    canCreateProjects: true,
    canWriteComments: true,
    canUpdateOnlineEvaluationRules: true,
    canUpdateAlerts: true,
    canAnnotateTraceSpanThread: true,
    canCreateDashboards: true,
    canTagTrace: true,
  },
  isPending: false,
};
