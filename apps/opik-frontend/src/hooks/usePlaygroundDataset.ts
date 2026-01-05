import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { useValidatedDatasetId } from "./useValidatedDatasetId";
import { useValidatedDatasetVersion } from "./useValidatedDatasetVersion";

interface UsePlaygroundDatasetReturn {
  datasetId: string | null; // "id" or "id::versionId" format
  versionName?: string;
  versionHash?: string;
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
    versionHash,
    setVersionKey: setVersionedDatasetId,
  } = useValidatedDatasetVersion();

  if (isVersioningEnabled) {
    return {
      datasetId: versionedDatasetId,
      versionName,
      versionHash,
      setDatasetId: setVersionedDatasetId,
    };
  }

  return {
    datasetId: legacyDatasetId,
    setDatasetId: setLegacyDatasetId,
  };
};
