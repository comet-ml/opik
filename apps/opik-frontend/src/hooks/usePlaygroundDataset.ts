import { useValidatedDatasetVersion } from "./useValidatedDatasetVersion";

interface UsePlaygroundDatasetReturn {
  datasetId: string | null; // "id" or "id::versionId" format
  versionName?: string;
  versionHash?: string;
  setDatasetId: (v: string | null) => void;
}

export const usePlaygroundDataset = (): UsePlaygroundDatasetReturn => {
  const {
    storedKey: versionedDatasetId,
    versionName,
    versionHash,
    setVersionKey: setVersionedDatasetId,
  } = useValidatedDatasetVersion();

  return {
    datasetId: versionedDatasetId,
    versionName,
    versionHash,
    setDatasetId: setVersionedDatasetId,
  };
};
