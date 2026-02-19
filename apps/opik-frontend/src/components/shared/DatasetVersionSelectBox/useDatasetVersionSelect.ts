import { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { Dataset, DatasetVersion } from "@/types/datasets";
import toLower from "lodash/toLower";

export const DEFAULT_LOADED_DATASETS = 1000;
const MAX_LOADED_DATASETS = 10000;

interface UseDatasetVersionSelectParams {
  workspaceName: string;
  search: string;
  openDatasetId: string | null;
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
  workspaceName,
  search,
  openDatasetId,
}: UseDatasetVersionSelectParams): UseDatasetVersionSelectReturn {
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data: datasetsData, isLoading: isLoadingDatasets } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: !isLoadedMore ? DEFAULT_LOADED_DATASETS : MAX_LOADED_DATASETS,
    },
    {
      placeholderData: keepPreviousData,
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
    if (!search) return datasets;
    const searchLower = toLower(search);
    return datasets.filter((dataset) =>
      toLower(dataset.name).includes(searchLower),
    );
  }, [datasets, search]);

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
