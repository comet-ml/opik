export interface Permissions {
  canViewExperiments: boolean;
  canViewDashboards: boolean;
  canDeleteProjects: boolean;
  canDeleteAnnotationQueues: boolean;
  canDeleteTraces: boolean;
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
  },
  isPending: false,
};
