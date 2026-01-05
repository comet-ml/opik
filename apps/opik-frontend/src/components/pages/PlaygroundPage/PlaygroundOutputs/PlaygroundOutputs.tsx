import { Check, Loader2 } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import React, { useCallback, useEffect, useMemo } from "react";
import PlaygroundOutputTable from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputTable";
import PlaygroundOutputActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundOutputActions";
import PlaygroundOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";
import StatusMessage from "@/components/shared/StatusMessage/StatusMessage";
import {
  usePromptIds,
  useSetDatasetVariables,
  useDatasetFilters,
  useSetDatasetFilters,
  useDatasetPage,
  useSetDatasetPage,
  useDatasetSize,
  useSetDatasetSize,
  useResetDatasetFilters,
} from "@/store/PlaygroundStore";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetLoadingStatus from "@/hooks/useDatasetLoadingStatus";
import {
  DatasetItem,
  DatasetItemColumn,
  DATASET_STATUS,
} from "@/types/datasets";
import { Filter, Filters } from "@/types/filters";
import { COLUMN_DATA_ID } from "@/types/shared";
import { parseDatasetVersionKey } from "@/utils/datasetVersionStorage";

interface PlaygroundOutputsProps {
  workspaceName: string;
  datasetId: string | null;
  versionName?: string;
  versionHash?: string;
  onChangeDatasetId: (id: string | null) => void;
}

const EMPTY_ITEMS: DatasetItem[] = [];
const EMPTY_COLUMNS: DatasetItemColumn[] = [];
const POLLING_INTERVAL_MS = 3000;

/**
 * Transform data column filters from "data.columnName" format to backend format.
 * This converts field="data.columnName" to field="data" with key="columnName".
 * This transformation is specific to dataset item filtering and should not be in generic filter processing.
 */
const transformDataColumnFilters = (filters: Filters): Filters => {
  const dataFieldPrefix = `${COLUMN_DATA_ID}.`;

  return filters.map((filter: Filter) => {
    if (filter.field.startsWith(dataFieldPrefix)) {
      const columnKey = filter.field.slice(dataFieldPrefix.length);
      return {
        ...filter,
        field: COLUMN_DATA_ID,
        key: columnKey,
      };
    }
    return filter;
  });
};

const PlaygroundOutputs = ({
  workspaceName,
  datasetId,
  versionName,
  versionHash,
  onChangeDatasetId,
}: PlaygroundOutputsProps) => {
  const promptIds = usePromptIds();
  const setDatasetVariables = useSetDatasetVariables();
  const filters = useDatasetFilters();
  const setFilters = useSetDatasetFilters();
  const page = useDatasetPage();
  const setPage = useSetDatasetPage();
  const size = useDatasetSize();
  const setSize = useSetDatasetSize();
  const resetDatasetFilters = useResetDatasetFilters();

  // Parse datasetId to extract plain ID for API calls (handles both "id" and "id::versionId" formats)
  const parsedDatasetId = useMemo(() => {
    if (!datasetId) return null;
    const parsed = parseDatasetVersionKey(datasetId);
    return parsed?.datasetId || datasetId;
  }, [datasetId]);

  const { data: dataset } = useDatasetById(
    { datasetId: parsedDatasetId! },
    {
      enabled: !!parsedDatasetId,
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  const { isProcessing, showSuccessMessage } = useDatasetLoadingStatus({
    datasetStatus: dataset?.status,
  });

  // Transform data column filters before passing to API
  const transformedFilters = useMemo(
    () => (filters ? transformDataColumnFilters(filters) : filters),
    [filters],
  );

  const {
    data: datasetItemsData,
    isLoading: isLoadingDatasetItems,
    isPlaceholderData: isPlaceholderDatasetItems,
    isFetching: isFetchingDatasetItems,
  } = useDatasetItemsList(
    {
      datasetId: parsedDatasetId!,
      page,
      size,
      truncate: true,
      filters: transformedFilters,
      versionId: versionHash, // Send hash, not ID
    },
    {
      enabled: !!parsedDatasetId,
      refetchInterval: isProcessing ? POLLING_INTERVAL_MS : false,
      placeholderData: parsedDatasetId ? keepPreviousData : undefined,
    },
  );

  const datasetItems = datasetItemsData?.content || EMPTY_ITEMS;
  const datasetColumns = datasetItemsData?.columns || EMPTY_COLUMNS;
  const total = datasetItemsData?.total || 0;

  const handleChangeDatasetId = useCallback(
    (id: string | null) => {
      resetDatasetFilters();
      if (!id) {
        setDatasetVariables([]);
      }
      onChangeDatasetId(id);
    },
    [onChangeDatasetId, resetDatasetFilters, setDatasetVariables],
  );

  const renderResult = () => {
    if (parsedDatasetId) {
      return (
        <div className="flex w-full flex-col pb-4 pt-2">
          {isProcessing && (
            <StatusMessage
              icon={Loader2}
              iconClassName="animate-spin"
              title="Dataset still loading"
              description="Experiments will run, but may not use the full dataset until loading completes."
              className="mb-2"
            />
          )}
          {showSuccessMessage && (
            <StatusMessage
              icon={Check}
              title="Dataset fully loaded"
              description="All items are now available."
              className="mb-2"
            />
          )}
          <PlaygroundOutputTable
            promptIds={promptIds}
            datasetItems={datasetItems}
            datasetColumns={datasetColumns}
            isLoadingDatasetItems={isLoadingDatasetItems}
            isFetchingData={isFetchingDatasetItems && isPlaceholderDatasetItems}
          />
        </div>
      );
    }

    return (
      <div className="flex w-full gap-[var(--item-gap)] pb-4 pt-2">
        {promptIds?.map((promptId) => (
          <PlaygroundOutput
            key={`output-${promptId}`}
            promptId={promptId}
            totalOutputs={promptIds.length}
          />
        ))}
      </div>
    );
  };

  useEffect(() => {
    setDatasetVariables(datasetColumns.map((c) => c.name));
  }, [setDatasetVariables, datasetColumns]);

  return (
    <div className="flex min-w-full flex-col">
      <PlaygroundOutputActions
        datasetId={datasetId}
        versionName={versionName}
        onChangeDatasetId={handleChangeDatasetId}
        datasetItems={datasetItems}
        datasetColumns={datasetColumns}
        workspaceName={workspaceName}
        loadingDatasetItems={isLoadingDatasetItems}
        filters={filters}
        onFiltersChange={setFilters}
        page={page}
        onChangePage={setPage}
        size={size}
        onChangeSize={setSize}
        total={total}
        isLoadingTotal={isProcessing}
      />
      {renderResult()}
    </div>
  );
};

export default PlaygroundOutputs;
