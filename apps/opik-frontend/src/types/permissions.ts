export interface Permissions {
  canViewExperiments: boolean;
  canViewDatasets: boolean;
}

export interface PermissionsContextValue {
  permissions: Permissions;
  isPending: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsContextValue = {
  permissions: {
    canViewExperiments: true,
    canViewDatasets: true,
  },
  isPending: false,
};
