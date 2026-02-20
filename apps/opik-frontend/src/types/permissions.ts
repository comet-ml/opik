export interface Permissions {
  canViewExperiments: boolean | null;
}

export const DEFAULT_PERMISSIONS: Permissions = {
  canViewExperiments: true,
};
