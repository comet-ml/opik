import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import useCurrentOrganization from "@/plugins/comet/useCurrentOrganization";

/**
 * Hook to check if workspace roles are enabled for the current organization.
 * Returns true only if both the feature flag is enabled AND the current organization
 * has workspaceRolesEnabled set to true.
 *
 * @returns boolean - true if workspace roles are enabled for the current organization
 */
export const useIsWorkspaceRolesEnabled = (): boolean => {
  const isPermissionsManagementEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.PERMISSIONS_MANAGEMENT_ENABLED,
  );

  const currentOrganization = useCurrentOrganization();

  return (
    isPermissionsManagementEnabled &&
    (currentOrganization?.workspaceRolesEnabled ?? false)
  );
};
