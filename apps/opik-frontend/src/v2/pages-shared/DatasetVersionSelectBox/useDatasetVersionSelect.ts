import { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { Dataset, DatasetVersion, DATASET_TYPE } from "@/types/datasets";
import toLower from "lodash/toLower";

export const DEFAULT_LOADED_DATASETS = 1000;
const MAX_LOADED_DATASETS = 10000;

interface UseDatasetVersionSelectParams {
  projectId?: string | null;
  search: string;
  openDatasetId: string | null;
  datasetType?: DATASET_TYPE;
}

export interface UseDatasetVersionSelectReturn {
  datasets: Dataset[];
  datasetTotal: number;
  isLoadingDatasets: boolean;
  versions: DatasetVersion[];
  isLoadingVersions: boolean;
  filteredDatasets: Dataset[];
  loadMore: () => void;
  hasMore: boolean;
}

export default function useDatasetVersionSelect({
  projectId,
  search,
  openDatasetId,
  datasetType,
}: UseDatasetVersionSelectParams): UseDatasetVersionSelectReturn {
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data: datasetsData, isLoading: isLoadingDatasets } =
    useProjectDatasetsList(
      {
        projectId: projectId!,
        page: 1,
        size: !isLoadedMore ? DEFAULT_LOADED_DATASETS : MAX_LOADED_DATASETS,
      },
      {
        placeholderData: keepPreviousData,
        enabled: !!projectId,
      },
    );

  const datasets = useMemo(
    () => datasetsData?.content || [],
    [datasetsData?.content],
  );
  const datasetTotal = datasetsData?.total ?? 0;

  // Only load versions when actively hovering a dataset
  const datasetIdForVersions = openDatasetId;

  const { data: versionsData, isLoading: isLoadingVersions } =
    useDatasetVersionsList(
      {
        datasetId: datasetIdForVersions || "",
        page: 1,
        size: 100, // Load all versions at once for submenu
      },
      {
        enabled: !!datasetIdForVersions,
        placeholderData: keepPreviousData,
      },
    );

  const versions = useMemo(
    () => versionsData?.content || [],
    [versionsData?.content],
  );

  const filteredDatasets = useMemo(() => {
    let result = datasets;
    if (datasetType) {
      result = result.filter((dataset) => dataset.type === datasetType);
    }
    if (search) {
      const searchLower = toLower(search);
      result = result.filter((dataset) =>
        toLower(dataset.name).includes(searchLower),
      );
    }
    return result;
  }, [datasets, search, datasetType]);

  const loadMore = useCallback(() => {
    setIsLoadedMore(true);
  }, []);

  const hasMore = datasetTotal > DEFAULT_LOADED_DATASETS && !isLoadedMore;

  return {
    datasets,
    datasetTotal,
    isLoadingDatasets,
    versions,
    isLoadingVersions,
    filteredDatasets,
    loadMore,
    hasMore,
  };
}
