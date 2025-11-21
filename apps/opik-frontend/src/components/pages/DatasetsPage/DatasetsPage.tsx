import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { useNavigate } from "@tanstack/react-router";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset } from "@/types/datasets";
import Loader from "@/components/shared/Loader/Loader";
import AddEditDatasetDialog from "@/components/pages/DatasetsPage/AddEditDatasetDialog";
import DatasetsActionsPanel from "@/components/pages/DatasetsPage/DatasetsActionsPanel";
import { DatasetRowActionsCell } from "@/components/pages/DatasetsPage/DatasetRowActionsCell";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";

export const getRowId = (d: Dataset) => d.id;

const SELECTED_COLUMNS_KEY = "datasets-selected-columns";
const COLUMNS_WIDTH_KEY = "datasets-columns-width";
const COLUMNS_ORDER_KEY = "datasets-columns-order";
const COLUMNS_SORT_KEY = "datasets-columns-sort";
const PAGINATION_SIZE_KEY = "datasets-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "dataset_items_count",
    label: "Item count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "most_recent_experiment_at",
    label: "Most recent experiment",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.most_recent_experiment_at),
  },
  {
    id: "most_recent_optimization_at",
    label: "Most recent optimization",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.most_recent_optimization_at),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const FILTERS_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "description",
  "dataset_items_count",
  "most_recent_experiment_at",
  "created_at",
];

const DatasetsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam(`filters`, JsonParam, {
    updateType: "replaceIn",
  });

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: `sorting`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = useDatasetsList(
    {
      workspaceName,
      filters,
      sorting: sortedColumns,
      search: search!,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const datasets = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );
  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const noDataText = noData ? "There are no datasets yet" : "No search results";

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

  const selectedRows: Dataset[] = useMemo(() => {
    return datasets.filter((row) => rowSelection[row.id]);
  }, [rowSelection, datasets]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Dataset>(),
      mapColumnDataFields<Dataset, Dataset>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.dataset,
        },
        sortable: isColumnSortable(COLUMN_NAME_ID, sortableBy),
      }),
      ...convertColumnDataToColumn<Dataset, Dataset>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      generateActionsColumDef({
        cell: DatasetRowActionsCell,
      }),
    ];
  }, [sortableBy, columnsOrder, selectedColumns]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewDatasetClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback(
    (row: Dataset) => {
      if (!row.id) return;

      navigate({
        to: "/$workspaceName/datasets/$datasetId",
        params: {
          datasetId: row.id,
          workspaceName,
        },
      });
    },
    [workspaceName, navigate],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Datasets</h1>
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_a_dataset]}
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <FiltersButton
            columns={FILTERS_COLUMNS}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <DatasetsActionsPanel datasets={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" size="sm" onClick={handleNewDatasetClick}>
            Create new dataset
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={datasets}
        onRowClick={handleRowClick}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewDatasetClick}>
                Create new dataset
              </Button>
            )}
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page}
          pageChange={setPage}
          size={size}
          sizeChange={setSize}
          total={total}
        ></DataTablePagination>
      </div>
      <AddEditDatasetDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={handleRowClick}
      />
    </div>
  );
};

export default DatasetsPage;
