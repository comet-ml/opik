export interface Permissions {
  canViewExperiments: boolean;
  canCreateExperiments: boolean;
  canViewDashboards: boolean;
  canCreateDashboards: boolean;
  canEditDashboards: boolean;
  canDeleteDashboards: boolean;
  canViewDatasets: boolean;
  canCreateDatasets: boolean;
  canEditDatasets: boolean;
  canDeleteDatasets: boolean;
  canDeleteProjects: boolean;
  canCreateAnnotationQueues: boolean;
  canEditAnnotationQueues: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
  canCreatePrompts: boolean;
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
  canUsePlayground: boolean;
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
    canCreateDatasets: true,
    canEditDatasets: true,
    canDeleteDatasets: true,
    canDeleteProjects: true,
    canCreateAnnotationQueues: true,
    canEditAnnotationQueues: true,
    canDeleteAnnotationQueues: true,
    canDeleteTraces: true,
    canCreatePrompts: true,
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
    canUsePlayground: true,
  },
  isPending: false,
};
