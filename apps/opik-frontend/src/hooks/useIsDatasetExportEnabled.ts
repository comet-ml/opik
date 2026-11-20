import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { usePermissions } from "@/contexts/PermissionsContext";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const useIsDatasetExportEnabled = (): boolean => {
  const isFeatureEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return isFeatureEnabled && canViewDatasets;
};

export default useIsDatasetExportEnabled;
