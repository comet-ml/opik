export interface Permissions {
  canViewExperiments: boolean;
  canViewDashboards: boolean;
  canViewDatasets: boolean;
  canDeleteProjects: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
  canDeletePrompts: boolean;
  canDeleteDatasets: boolean;
  canDeleteOptimizationRuns: boolean;
  canUpdateUserRole: boolean;
  canUpdateAIProviders: boolean;
}

export interface PermissionsContextValue {
  permissions: Permissions;
  isPending: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsContextValue = {
  permissions: {
    canViewExperiments: true,
    canViewDashboards: true,
    canViewDatasets: true,
    canDeleteProjects: true,
    canDeleteAnnotationQueues: true,
    canDeleteTraces: true,
    canDeletePrompts: true,
    canDeleteDatasets: true,
    canDeleteOptimizationRuns: true,
    canUpdateUserRole: true,
    canUpdateAIProviders: true,
  },
  isPending: false,
};
