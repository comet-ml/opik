import React, { useCallback, useMemo, useState } from "react";
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
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import { Dataset, DATASET_TYPE, DatasetListType } from "@/types/datasets";
import Loader from "@/shared/Loader/Loader";
import AddEditDatasetDialog from "@/v2/pages-shared/datasets/AddEditDatasetDialog/AddEditDatasetDialog";
import AddEditTestSuiteDialog from "@/v2/pages-shared/datasets/AddEditTestSuiteDialog/AddEditTestSuiteDialog";
import CreateDatasetSidebar from "@/v2/pages-shared/datasets/CreateDatasetSidebar/CreateDatasetSidebar";
import DatasetActionsPanel from "@/v2/pages-shared/datasets/DatasetActionsPanel/DatasetActionsPanel";
import { createDatasetRowActionsCell } from "@/v2/pages-shared/datasets/DatasetRowActionsCell/DatasetRowActionsCell";
import { Plus } from "lucide-react";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
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
import TextCell from "@/shared/DataTableCells/TextCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { usePermissions } from "@/contexts/PermissionsContext";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { buildDocsUrl } from "@/lib/utils";
import { Filter } from "@/types/filters";
import emptyTestSuitesLightUrl from "/images/empty-test-suites-light.svg";
import emptyTestSuitesDarkUrl from "/images/empty-test-suites-dark.svg";

type DatasetListPageProps = {
  type: DatasetListType;
};

const TYPE_CONFIG = {
  dataset: {
    title: "Datasets",
    docsUrl: "/evaluation/manage_datasets",
    entityName: "datasets",
    createButtonText: "Create dataset",
    noDataText: "There are no datasets yet",
    emptyStateTitle: "No datasets yet",
    emptyStateDescription:
      "Get started by creating your first dataset.\nDefine inputs and expected outputs to evaluate and optimize your agent.",
    emptyStatePrimaryActionLabel: "Create your first dataset",
    storagePrefix: "datasets",
    typeFilter: [
      {
        id: "type",
        field: "type",
        type: COLUMN_TYPE.string,
        operator: "=" as const,
        value: DATASET_TYPE.DATASET,
      },
    ] as Filter[],
    useSimpleDialog: true,
    rowActionsEntityName: "dataset",
    detailRoute:
      "/$workspaceName/projects/$projectId/datasets/$datasetId" as const,
    detailParamName: "datasetId" as const,
  },
  test_suite: {
    title: "Test suites",
    // TODO: replace with test suites documentation URL once it exists
    docsUrl: "/evaluation/manage_datasets",
    entityName: "test suites",
    createButtonText: "Create test suite",
    noDataText: "There are no test suites yet",
    emptyStateTitle: "No test suites yet",
    emptyStateDescription:
      "Get started by creating your first test suite.\nDefine test cases with expected outputs and scoring to compare and optimize your configurations.",
    emptyStatePrimaryActionLabel: "Create your first suite",
    storagePrefix: "test-suites",
    typeFilter: [
      {
        id: "type",
        field: "type",
        type: COLUMN_TYPE.string,
        operator: "=" as const,
        value: DATASET_TYPE.TEST_SUITE,
      },
    ] as Filter[],
    useSimpleDialog: false,
    rowActionsEntityName: "test suite",
    detailRoute:
      "/$workspaceName/projects/$projectId/test-suites/$suiteId" as const,
    detailParamName: "suiteId" as const,
  },
} as const;

const DEFAULT_COLUMNS: ColumnData<Dataset>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
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

const FILTERS_COLUMNS: ColumnData<Dataset>[] = [
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
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "description",
  "dataset_items_count",
  "most_recent_experiment_at",
  "last_updated_at",
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const getRowId = (d: Dataset) => d.id;

const DatasetListPage: React.FunctionComponent<DatasetListPageProps> = ({
  type,
}) => {
  const config = TYPE_CONFIG[type];
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();

  const isDatasetExportEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_EXPORT_ENABLED,
  );

  const {
    permissions: { canCreateDatasets, canEditDatasets, canDeleteDatasets },
  } = usePermissions();

  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam(`filters`, JsonParam, {
    updateType: "replaceIn",
  });

  const COLUMNS_SORT_KEY = `${config.storagePrefix}-columns-sort`;
  const SELECTED_COLUMNS_KEY = `${config.storagePrefix}-selected-columns`;
  const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
  const COLUMNS_WIDTH_KEY = `${config.storagePrefix}-columns-width`;
  const COLUMNS_ORDER_KEY = `${config.storagePrefix}-columns-order`;
  const PAGINATION_SIZE_KEY = `${config.storagePrefix}-pagination-size`;

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

  const combinedFilters = useMemo(
    () => [...config.typeFilter, ...filters],
    [config.typeFilter, filters],
  );

  const { data, isPending, isPlaceholderData, isFetching } =
    useProjectDatasetsList(
      {
        projectId: activeProjectId!,
        filters: combinedFilters,
        sorting: sortedColumns,
        search: search!,
        page,
        size,
      },
      {
        placeholderData: keepPreviousData,
        enabled: !!activeProjectId,
      },
    );

  const datasets = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );
  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const isEmpty = noData && datasets.length === 0;
  const noDataText = noData ? config.noDataText : "No search results";

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
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

  const EditDialog = config.useSimpleDialog
    ? AddEditDatasetDialog
    : AddEditTestSuiteDialog;

  const RowActionsCell = useMemo(
    () =>
      createDatasetRowActionsCell({
        entityName: config.rowActionsEntityName,
        EditDialog,
      }),
    [config.rowActionsEntityName, EditDialog],
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
              cell: RowActionsCell,
            }),
          ]
        : []),
    ];
  }, [
    sortableBy,
    columnsOrder,
    selectedColumns,
    showActionsColumn,
    RowActionsCell,
  ]);

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

  const handleCreateClick = useCallback(() => {
    setOpenDialog(true);
  }, []);

  const handleRowClick = useCallback(
    (row: Dataset) => {
      if (!row.id || !activeProjectId) return;

      navigate({
        to: config.detailRoute,
        params: {
          [config.detailParamName]: row.id,
          workspaceName,
          projectId: activeProjectId,
        } as Record<string, string>,
      });
    },
    [
      workspaceName,
      activeProjectId,
      navigate,
      config.detailRoute,
      config.detailParamName,
    ],
  );

  if (isPending || (isPlaceholderData && datasets.length === 0)) {
    return <Loader />;
  }

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-4 flex min-h-7 items-center justify-between">
        <h1 className="comet-body-accented truncate break-words">
          {config.title}
        </h1>
        {canCreateDatasets && (
          <Button variant="default" size="xs" onClick={handleCreateClick}>
            <Plus className="mr-1 size-4" />
            {config.createButtonText}
          </Button>
        )}
      </div>
      {isEmpty ? (
        <PageEmptyState
          lightImageUrl={emptyTestSuitesLightUrl}
          darkImageUrl={emptyTestSuitesDarkUrl}
          title={config.emptyStateTitle}
          description={config.emptyStateDescription}
          primaryActionLabel={
            canCreateDatasets ? config.emptyStatePrimaryActionLabel : undefined
          }
          onPrimaryAction={canCreateDatasets ? handleCreateClick : undefined}
          docsUrl={buildDocsUrl(config.docsUrl)}
        />
      ) : (
        <>
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
                layout="icon"
              />
            </div>
            <div className="flex items-center gap-2">
              {canDeleteDatasets && (
                <>
                  <DatasetActionsPanel
                    datasets={selectedRows}
                    entityName={config.entityName}
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
                {noData && canCreateDatasets && (
                  <Button variant="link" onClick={handleCreateClick}>
                    {config.createButtonText}
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
        </>
      )}
      <CreateDatasetSidebar
        type={type}
        open={openDialog}
        setOpen={setOpenDialog}
        onDatasetCreated={handleRowClick}
      />
    </div>
  );
};

export default DatasetListPage;
