export interface Permissions {
  canViewExperiments: boolean | null;
  canViewDashboards: boolean | null;
}

export const DEFAULT_PERMISSIONS: Permissions = {
  canViewExperiments: true,
  canViewDashboards: true,
};
