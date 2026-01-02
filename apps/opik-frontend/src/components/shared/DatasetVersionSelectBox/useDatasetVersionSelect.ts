import { useCallback, useMemo } from "react";
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
  isLoadedMore: boolean;
  openDatasetId: string | null;
  selectedDatasetId: string | null;
}

export interface UseDatasetVersionSelectReturn {
  datasets: Dataset[];
  datasetTotal: number;
  isLoadingDatasets: boolean;
  versions: DatasetVersion[];
  isLoadingVersions: boolean;
  filteredDatasets: Dataset[];
  loadMore: () => void;
}

export default function useDatasetVersionSelect({
  workspaceName,
  search,
  isLoadedMore,
  openDatasetId,
  selectedDatasetId,
}: UseDatasetVersionSelectParams): UseDatasetVersionSelectReturn {
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

  // Load versions for either the hovered dataset or the selected dataset
  const datasetIdForVersions = openDatasetId || selectedDatasetId;

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
    // Load more is handled by parent component via isLoadedMore state
  }, []);

  return {
    datasets,
    datasetTotal,
    isLoadingDatasets,
    versions,
    isLoadingVersions,
    filteredDatasets,
    loadMore,
  };
}
