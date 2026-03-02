export interface Permissions {
  canViewExperiments: boolean;
  canViewDashboards: boolean;
  canDeleteProjects: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
  canDeletePrompts: boolean;
  canDeleteDatasets: boolean;
}

export interface PermissionsContextValue {
  permissions: Permissions;
  isPending: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsContextValue = {
  permissions: {
    canViewExperiments: true,
    canViewDashboards: true,
    canDeleteProjects: true,
    canDeleteAnnotationQueues: true,
    canDeleteTraces: true,
    canDeletePrompts: true,
    canDeleteDatasets: true,
  },
  isPending: false,
};
