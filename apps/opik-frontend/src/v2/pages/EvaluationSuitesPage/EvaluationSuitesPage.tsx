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

import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset } from "@/types/datasets";
import Loader from "@/shared/Loader/Loader";
import AddEditEvaluationSuiteDialog from "@/v2/pages-shared/datasets/AddEditEvaluationSuiteDialog/AddEditEvaluationSuiteDialog";
import DatasetActionsPanel from "@/v2/pages-shared/datasets/DatasetActionsPanel/DatasetActionsPanel";
import { createDatasetRowActionsCell } from "@/v2/pages-shared/datasets/DatasetRowActionsCell/DatasetRowActionsCell";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { buildDocsUrl } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  DEFAULT_SELECTED_COLUMNS,
  TYPE_LABELS,
} from "@/v2/pages/EvaluationSuitesPage/columns";
import TextCell from "@/shared/DataTableCells/TextCell";
import DatasetTypeCell from "@/v2/pages/EvaluationSuitesPage/DatasetTypeCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { usePermissions } from "@/contexts/PermissionsContext";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const EvaluationSuiteRowActionsCell = createDatasetRowActionsCell({
  entityName: "dataset",
  EditDialog: AddEditEvaluationSuiteDialog,
});

const getRowId = (d: Dataset) => d.id;

const SELECTED_COLUMNS_KEY = "evaluation-suites-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const SELECTED_COLUMNS_KEY_V3 = `${SELECTED_COLUMNS_KEY}-v3`;
const COLUMNS_WIDTH_KEY = "evaluation-suites-columns-width";
const COLUMNS_ORDER_KEY = "evaluation-suites-columns-order";
const COLUMNS_SORT_KEY = "evaluation-suites-columns-sort";
const PAGINATION_SIZE_KEY = "evaluation-suites-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.string,
    cell: DatasetTypeCell as never,
  },
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
    cell: TimeCell as never,
  },
  {
    id: "most_recent_optimization_at",
    label: "Most recent optimization",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
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
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.category,
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
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const EvaluationSuitesPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const isDatasetExportEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );

  const {
    permissions: { canEditDatasets, canDeleteDatasets },
  } = usePermissions();

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

  const { data, isPending, isPlaceholderData, isFetching } = useDatasetsList(
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
    SELECTED_COLUMNS_KEY_V3,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY_V2,
        DEFAULT_SELECTED_COLUMNS,
      ),
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

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        type: {
          keyComponentProps: {
            options: Object.entries(TYPE_LABELS).map(([value, label]) => ({
              value,
              label,
            })),
            placeholder: "Select type",
          },
        },
      },
    }),
    [],
  );

  const selectedRows: Dataset[] = useMemo(() => {
    return datasets.filter((row) => rowSelection[row.id]);
  }, [rowSelection, datasets]);

  const showActionsColumn =
    canEditDatasets || canDeleteDatasets || isDatasetExportEnabled;

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Dataset>(),
      ...convertColumnDataToColumn<Dataset, Dataset>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      ...(showActionsColumn
        ? [
            generateActionsColumDef<Dataset>({
              cell: EvaluationSuiteRowActionsCell,
            }),
          ]
        : []),
    ];
  }, [sortableBy, columnsOrder, selectedColumns, showActionsColumn]);

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

  const handleNewSuiteClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback(
    (row: Dataset) => {
      if (!row.id) return;

      navigate({
        to: "/$workspaceName/evaluation-suites/$suiteId",
        params: {
          suiteId: row.id,
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
      <div className="comet-body-s mb-4 text-muted-slate">
        A dataset is a collection of inputs and expected outputs used to
        evaluate your LLM application.{" "}
        <a
          href={buildDocsUrl("/evaluation/manage_datasets")}
          target="_blank"
          rel="noreferrer"
          className="text-primary"
        >
          Read more
        </a>
      </div>
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
            config={filtersConfig as never}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          {canDeleteDatasets && (
            <>
              <DatasetActionsPanel
                datasets={selectedRows}
                entityName="datasets"
              />
              <Separator orientation="vertical" className="mx-2 h-4" />
            </>
          )}
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" size="sm" onClick={handleNewSuiteClick}>
            Create new
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
              <Button variant="link" onClick={handleNewSuiteClick}>
                Create new
              </Button>
            )}
          </DataTableNoData>
        }
        showLoadingOverlay={isPlaceholderData && isFetching}
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
      <AddEditEvaluationSuiteDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={handleRowClick}
      />
    </div>
  );
};

export default EvaluationSuitesPage;
