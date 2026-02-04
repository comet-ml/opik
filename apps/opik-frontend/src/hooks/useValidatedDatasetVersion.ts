import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { PLAYGROUND_SELECTED_DATASET_VERSION_KEY } from "@/constants/llm";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";

const MAX_VERSIONS_TO_FETCH = 1000;

interface UseValidatedDatasetVersionReturn {
  storedKey: string | null;
  versionName: string | undefined;
  versionId: string | undefined;
  versionHash: string | undefined;
  setVersionKey: (key: string | null) => void;
}

/**
 * Validates stored dataset version against backend.
 * Returns null/undefined if version doesn't exist.
 */
export const useValidatedDatasetVersion =
  (): UseValidatedDatasetVersionReturn => {
    const [localStorageValue, setStoredKey] = useLocalStorageState<
      string | null
    >(PLAYGROUND_SELECTED_DATASET_VERSION_KEY, {
      defaultValue: null,
    });

    const parsed = parseDatasetVersionKey(localStorageValue);

    const { data } = useDatasetVersionsList(
      {
        datasetId: parsed?.datasetId || "",
        page: 1,
        size: MAX_VERSIONS_TO_FETCH,
      },
      {
        enabled: !!parsed,
        retry: false,
        placeholderData: keepPreviousData,
      },
    );

    if (!parsed) {
      return {
        storedKey: null,
        versionName: undefined,
        versionId: undefined,
        versionHash: undefined,
        setVersionKey: setStoredKey,
      };
    }

    const version = data?.content?.find((v) => v.id === parsed.versionId);

    if (!version) {
      return {
        storedKey: null,
        versionName: undefined,
        versionId: undefined,
        versionHash: undefined,
        setVersionKey: setStoredKey,
      };
    }

    return {
      storedKey: localStorageValue,
      versionName: version.version_name,
      versionId: version.id,
      versionHash: version.version_hash,
      setVersionKey: setStoredKey,
    };
  };
