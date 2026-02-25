import { WRITE_ACTIONS_ENABLED } from "@/config";

export interface Permissions {
  canViewExperiments: boolean;
  canInteractWithApp: boolean;
}

export interface PermissionsContextValue {
  permissions: Permissions;
  isPending: boolean;
}

export const DEFAULT_PERMISSIONS: PermissionsContextValue = {
  permissions: {
    canViewExperiments: true,
    canInteractWithApp: WRITE_ACTIONS_ENABLED,
  },
  isPending: false,
};
