import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useValidatedDatasetId } from "./useValidatedDatasetId";
import { useValidatedDatasetVersion } from "./useValidatedDatasetVersion";

interface UsePlaygroundDatasetReturn {
  datasetId: string | null; // "id" or "id::hash" format
  versionName?: string;
  setDatasetId: (v: string | null) => void;
}

export const usePlaygroundDataset = (): UsePlaygroundDatasetReturn => {
  const isVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
  );

  const [legacyDatasetId, setLegacyDatasetId] = useValidatedDatasetId();
  const {
    storedKey: versionedDatasetId,
    versionName,
    setVersionKey: setVersionedDatasetId,
  } = useValidatedDatasetVersion();

  if (isVersioningEnabled) {
    return {
      datasetId: versionedDatasetId,
      versionName,
      setDatasetId: setVersionedDatasetId,
    };
  }

  return {
    datasetId: legacyDatasetId,
    versionName: undefined,
    setDatasetId: setLegacyDatasetId,
  };
};
