import React, { useCallback, useMemo, useRef, useState } from "react";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import get from "lodash/get";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import { Check, Loader2 } from "lucide-react";

import Loader from "@/components/shared/Loader/Loader";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import useDatasetLoadingStatus from "@/hooks/useDatasetLoadingStatus";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DateTag from "@/components/shared/DateTag/DateTag";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import StatusMessage from "@/components/shared/StatusMessage/StatusMessage";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import { DatasetItem, DATASET_STATUS } from "@/types/datasets";
import { Filter, Filters } from "@/types/filters";
import {
  COLUMN_DATA_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import DatasetItemEditor from "./DatasetItemEditor/DatasetItemEditor";
import DatasetItemsActionsPanel from "./DatasetItemsActionsPanel";
import { DatasetItemRowActionsCell } from "./DatasetItemRowActionsCell";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import SelectAllBanner from "@/components/shared/SelectAllBanner/SelectAllBanner";
import AddEditDatasetItemDialog from "@/components/pages/DatasetItemsPage/AddEditDatasetItemDialog";
import AddDatasetItemSidebar from "./AddDatasetItemSidebar";
import DatasetTagsList from "@/components/pages/DatasetItemsPage/DatasetTagsList";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { convertColumnDataToColumn, injectColumnCallback } from "@/lib/table";
import { buildDocsUrl } from "@/lib/utils";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import UseDatasetDropdown from "@/components/pages/DatasetItemsPage/UseDatasetDropdown";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { DATASET_ITEM_DATA_PREFIX } from "@/constants/datasets";

/**
 * Transform data column filters from "data.columnName" format to backend format.
 * This converts field="data.columnName" to field="data" with key="columnName".
 * This transformation is specific to DatasetItemsPage and should not be in generic filter processing.
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

const getRowId = (d: DatasetItem) => d.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "created_at",
  "tags",
];

const SELECTED_COLUMNS_KEY = "dataset-items-selected-columns";
const COLUMNS_WIDTH_KEY = "dataset-items-columns-width";
const COLUMNS_ORDER_KEY = "dataset-items-columns-order";
const DYNAMIC_COLUMNS_KEY = "dataset-items-dynamic-columns";
const PAGINATION_SIZE_KEY = "dataset-items-pagination-size";
const ROW_HEIGHT_KEY = "dataset-items-row-height";
const POLLING_INTERVAL_MS = 3000;

const DatasetItemsPage = () => {
  const datasetId = useDatasetIdFromURL();

  const [activeRowId = "", setActiveRowId] = useQueryParam("row", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam<Filters, Filters>(
    "filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [isAllItemsSelected, setIsAllItemsSelected] = useState(false);

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [openAddSidebar, setOpenAddSidebar] = useState<boolean>(false);

  const { data: dataset, isPending: isDatasetPending } = useDatasetById(
    {
      datasetId,
    },
    {
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

  const { data, isPending, isPlaceholderData, isFetching } =
    useDatasetItemsList(
      {
        datasetId,
        filters: transformedFilters,
        page: page as number,
        size: size as number,
        search: search!,
        truncate: false,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: isProcessing ? POLLING_INTERVAL_MS : false,
      },
    );
  const totalCount = data?.total ?? 0;

  const datasetColumns = useMemo(
    () =>
      (data?.columns ?? []).sort((c1, c2) => c1.name.localeCompare(c2.name)),
    [data?.columns],
  );

  const { refetch: refetchExportData } = useDatasetItemsList(
    {
      datasetId,
      filters: transformedFilters,
      page: page as number,
      size: size as number,
      search: search!,
      truncate: false,
    },
    {
      enabled: false,
    },
  );

  const { refetch: refetchAllItemsForExport } = useDatasetItemsList(
    {
      datasetId,
      filters: transformedFilters,
      page: 1,
      size: totalCount || 1,
      search: search!,
      truncate: false,
    },
    {
      enabled: false,
    },
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const rows: Array<DatasetItem> = useMemo(() => data?.content ?? [], [data]);
  const noDataText = "There are no dataset items yet";

  const handleSearchChange = useCallback(
    (newSearch: string | null) => {
      setSearch(newSearch);
      if (page !== 1) {
        setPage(1);
      }
    },
    [setSearch, setPage, page],
  );

  const selectedRows: DatasetItem[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowSelectionChange: typeof setRowSelection = useCallback(
    (updaterOrValue) => {
      setRowSelection((prev) => {
        const next =
          typeof updaterOrValue === "function"
            ? updaterOrValue(prev)
            : updaterOrValue;

        // Reset isAllItemsSelected if selection count decreases (row deselected)
        if (
          isAllItemsSelected &&
          Object.keys(next).length < Object.keys(prev).length
        ) {
          setIsAllItemsSelected(false);
        }

        return next;
      });
    },
    [isAllItemsSelected],
  );

  const effectiveIsAllItemsSelected = useMemo(() => {
    return (
      isAllItemsSelected &&
      selectedRows.length === rows.length &&
      rows.length > 0
    );
  }, [isAllItemsSelected, selectedRows.length, rows.length]);

  const getDataForExport = useCallback(async (): Promise<DatasetItem[]> => {
    const result = effectiveIsAllItemsSelected
      ? await refetchAllItemsForExport()
      : await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    if (effectiveIsAllItemsSelected) {
      return result.data.content;
    }

    const selectedIds = Object.keys(rowSelection);

    return result.data.content.filter((row) => selectedIds.includes(row.id));
  }, [
    refetchExportData,
    refetchAllItemsForExport,
    rowSelection,
    effectiveIsAllItemsSelected,
  ]);

  const dynamicDatasetColumns = useMemo(() => {
    return datasetColumns.map<DynamicColumn>((c) => ({
      id: `${DATASET_ITEM_DATA_PREFIX}.${c.name}`,
      label: c.name,
      columnType: mapDynamicColumnTypesToColumnType(c.types),
    }));
  }, [datasetColumns]);

  const dynamicColumnsIds = useMemo(
    () => dynamicDatasetColumns.map((c) => c.id),
    [dynamicDatasetColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo(() => {
    const retVal: ColumnData<DatasetItem>[] = [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        customMeta: {
          asId: true,
        },
      },
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row) => get(row, ["data", label], ""),
            cell: AutodetectCell as never,
          }) as ColumnData<DatasetItem>,
      ),
    ];

    retVal.push({
      id: "tags",
      label: "Tags",
      type: COLUMN_TYPE.list,
      iconType: "tags",
      accessorFn: (row) => row.tags || [],
      cell: ListCell as never,
    });

    retVal.push({
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      cell: TimeCell as never,
    });

    retVal.push({
      id: "last_updated_at",
      label: "Last updated",
      type: COLUMN_TYPE.time,
      cell: TimeCell as never,
    });

    retVal.push({
      id: "created_by",
      label: "Created by",
      type: COLUMN_TYPE.string,
    });

    return retVal;
  }, [dynamicDatasetColumns]);

  const filtersColumnData = useMemo(() => {
    // Add each data column as a separate filter option with field prefix "data."
    // This will be transformed to field="data" and key=columnName when processing
    const dataFilterColumns = datasetColumns.map((c) => ({
      id: `${COLUMN_DATA_ID}.${c.name}`,
      label: c.name,
      type: COLUMN_TYPE.string,
    }));

    return [
      {
        id: "id",
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      ...dataFilterColumns,
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags" as const,
      },
    ];
  }, [datasetColumns]);

  const handleRowClick = useCallback(
    (row: DatasetItem) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const columns = useMemo(() => {
    const convertedColumns = convertColumnDataToColumn<
      DatasetItem,
      DatasetItem
    >(columnsData, {
      columnsOrder,
      selectedColumns,
    });

    return [
      generateSelectColumDef<DatasetItem>(),
      ...injectColumnCallback(convertedColumns, COLUMN_ID_ID, handleRowClick),
      generateActionsColumDef({
        cell: DatasetItemRowActionsCell,
      }),
    ];
  }, [columnsData, columnsOrder, selectedColumns, handleRowClick]);

  const columnsToExport = useMemo(() => {
    return columns
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const handleNewDatasetItemClick = useCallback(() => {
    if (data?.total && data.total > 0) {
      setOpenAddSidebar(true);
    } else {
      setOpenDialog(true);
      resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    }
  }, [data?.total]);

  const handleClose = useCallback(() => setActiveRowId(""), [setActiveRowId]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleClearSelection = useCallback(() => {
    setRowSelection({});
    setIsAllItemsSelected(false);
  }, []);

  const showSelectAllBanner =
    selectedRows.length > 0 &&
    selectedRows.length === rows.length &&
    selectedRows.length < totalCount;

  if (isPending || isDatasetPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <h1 className="comet-title-l truncate break-words">
            {dataset?.name}
          </h1>
          <div className="flex items-center gap-2">
            <UseDatasetDropdown
              datasetName={dataset?.name}
              datasetId={datasetId}
            />
          </div>
        </div>
        {dataset?.description && (
          <div className="-mt-3 mb-4 text-muted-slate">
            {dataset.description}
          </div>
        )}
        {dataset?.created_at && (
          <div className="mb-2 flex gap-2 overflow-x-auto">
            <DateTag
              date={dataset?.created_at}
              resource={RESOURCE_TYPE.dataset}
            />
          </div>
        )}
        <DatasetTagsList
          tags={dataset?.tags ?? []}
          dataset={dataset}
          datasetId={datasetId}
        />
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={handleSearchChange}
            placeholder="Search"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={filtersColumnData}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <DatasetItemsActionsPanel
            getDataForExport={getDataForExport}
            selectedDatasetItems={selectedRows}
            datasetId={datasetId}
            datasetName={dataset?.name ?? ""}
            columnsToExport={columnsToExport}
            dynamicColumns={dynamicColumnsIds}
            isAllItemsSelected={effectiveIsAllItemsSelected}
            filters={filters}
            search={search ?? ""}
            totalCount={totalCount}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={columnsData}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button
            variant="default"
            size="sm"
            onClick={handleNewDatasetItemClick}
          >
            Create dataset item
          </Button>
        </div>
      </div>
      {isProcessing && (
        <StatusMessage
          icon={Loader2}
          iconClassName="animate-spin"
          title="Your dataset is still loading"
          description="Some results or counts may update as more data becomes available. You can continue exploring while the full dataset loads."
          className="mb-4"
        />
      )}
      {showSuccessMessage && (
        <StatusMessage
          icon={Check}
          title="Your dataset fully loaded"
          description="All items are now available."
          className="mb-4"
        />
      )}
      {showSelectAllBanner && (
        <SelectAllBanner
          selectedCount={isAllItemsSelected ? totalCount : selectedRows.length}
          totalCount={totalCount}
          onSelectAll={() => setIsAllItemsSelected(true)}
          onClearSelection={handleClearSelection}
        />
      )}
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        showLoadingOverlay={isPlaceholderData && isFetching}
        selectionConfig={{
          rowSelection,
          setRowSelection: handleRowSelectionChange,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            <Button variant="link">
              <a
                href={buildDocsUrl(
                  "/evaluation/manage_datasets",
                  "#insert-items",
                )}
                target="_blank"
                rel="noreferrer"
              >
                Check our documentation
              </a>
            </Button>
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
          isLoadingTotal={isProcessing}
        />
      </div>
      <DatasetItemEditor
        datasetItemId={activeRowId as string}
        datasetId={datasetId}
        columns={datasetColumns}
        onClose={handleClose}
        isOpen={Boolean(activeRowId)}
        rows={rows}
        setActiveRowId={setActiveRowId}
      />

      <AddEditDatasetItemDialog
        key={resetDialogKeyRef.current}
        datasetId={datasetId}
        open={openDialog}
        setOpen={setOpenDialog}
      />

      <AddDatasetItemSidebar
        datasetId={datasetId}
        open={openAddSidebar}
        setOpen={setOpenAddSidebar}
        columns={datasetColumns}
      />
    </div>
  );
};

export default DatasetItemsPage;
