import React, { useCallback, useEffect, useMemo } from "react";
import PlaygroundOutputTable from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputTable/PlaygroundOutputTable";
import PlaygroundOutputActions from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutputActions/PlaygroundOutputActions";
import PlaygroundOutput from "@/components/pages/PlaygroundPage/PlaygroundOutputs/PlaygroundOutput";
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
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { Filter, Filters } from "@/types/filters";
import { COLUMN_DATA_ID } from "@/types/shared";
import { keepPreviousData } from "@tanstack/react-query";

interface PlaygroundOutputsProps {
  workspaceName: string;
  datasetId: string | null;
  onChangeDatasetId: (id: string | null) => void;
}

const EMPTY_ITEMS: DatasetItem[] = [];
const EMPTY_COLUMNS: DatasetItemColumn[] = [];

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
      datasetId: datasetId!,
      page,
      size,
      truncate: true,
      filters: transformedFilters,
    },
    {
      enabled: !!datasetId,
      placeholderData: keepPreviousData,
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
    if (datasetId) {
      return (
        <div className="flex w-full pb-4 pt-2">
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
        datasetItems={datasetItems}
        datasetColumns={datasetColumns}
        workspaceName={workspaceName}
        onChangeDatasetId={handleChangeDatasetId}
        loadingDatasetItems={isLoadingDatasetItems}
        filters={filters}
        onFiltersChange={setFilters}
        page={page}
        onChangePage={setPage}
        size={size}
        onChangeSize={setSize}
        total={total}
      />
      {renderResult()}
    </div>
  );
};

export default PlaygroundOutputs;
