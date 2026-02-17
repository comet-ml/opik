import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import {
  ColumnDef,
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import round from "lodash/round";

import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { EvaluatorsRule } from "@/types/automations";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import StatusCell from "@/components/shared/DataTableCells/StatusCell";
import useRulesList from "@/api/automations/useRulesList";
import { formatDate } from "@/lib/date";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import NoRulesPage from "@/components/pages-shared/automations/NoRulesPage";
import AddEditRuleDialog from "@/components/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import RulesActionsPanel from "@/components/pages-shared/automations/RulesActionsPanel";
import RuleRowActionsCell from "@/components/pages-shared/automations/RuleRowActionsCell";
import RuleLogsCell from "@/components/pages-shared/automations/RuleLogsCell";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { capitalizeFirstLetter } from "@/lib/utils";
import { getUIRuleScope } from "@/components/pages-shared/automations/AddEditRuleDialog/helpers";

const getRowId = (d: EvaluatorsRule) => d.id;

const DEFAULT_COLUMNS: ColumnData<EvaluatorsRule>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    sortable: true,
  },
  {
    id: "projects",
    label: "Projects",
    type: COLUMN_TYPE.string,
    accessorFn: (row) =>
      row.projects && row.projects.length > 0
        ? row.projects.map((p) => p.project_name).join(", ")
        : "N/A",
  },
  {
    id: "enabled",
    label: "Status",
    type: COLUMN_TYPE.string,
    cell: StatusCell as never,
  },
  {
    id: "sampling_rate",
    label: "Sampling rate",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => `${round(row.sampling_rate * 100, 1)}%`,
  },
  {
    id: "type",
    label: "Scope",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => capitalizeFirstLetter(getUIRuleScope(row.type)),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
  },
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
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

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "projects",
  "enabled",
  "sampling_rate",
  "type",
  "last_updated_at",
];

const SELECTED_COLUMNS_KEY = "workspace-rules-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "workspace-rules-columns-width";
const COLUMNS_ORDER_KEY = "workspace-rules-columns-order";
const COLUMNS_SORT_KEY = "workspace-rules-columns-sort";
const PAGINATION_SIZE_KEY = "workspace-rules-pagination-size";

export const OnlineEvaluationPage: React.FC = () => {
  const resetDialogKeyRef = useRef(0);
  const [openDialogForCreate, setOpenDialogForCreate] =
    useState<boolean>(false);
  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [editRuleId, setEditRuleId] = useQueryParam("editRule", StringParam, {
    updateType: "replaceIn",
  });

  const [cloneRuleId, setCloneRuleId] = useQueryParam(
    "cloneRule",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

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

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

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

  const { data, isPending, isPlaceholderData, isFetching } = useRulesList(
    {
      page: page as number,
      size: size as number,
      search: search as string,
      filters,
      sorting: sortedColumns,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );
  const noData = !search && filters.length === 0;
  const noDataText = noData ? `There are no rules yet` : "No search results";

  // Backend now enriches projects (ID + name pairs) from projectIds
  const rows: EvaluatorsRule[] = useMemo(() => data?.content ?? [], [data]);

  const editingRule = rows.find((r) => r.id === editRuleId);
  const cloningRule = rows.find((r) => r.id === cloneRuleId);
  const isDialogOpen =
    Boolean(editingRule) || Boolean(cloningRule) || openDialogForCreate;

  // Determine which rule to pass and what mode to use
  const dialogRule = editingRule || cloningRule;
  const dialogMode = editingRule ? "edit" : cloningRule ? "clone" : "create";

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_NAME_ID],
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

  const selectedRows: EvaluatorsRule[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleOpenEditDialog = useCallback(
    (ruleId: string) => {
      setEditRuleId(ruleId);
      resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    },
    [setEditRuleId],
  );

  const handleOpenCloneDialog = useCallback(
    (ruleId: string) => {
      setCloneRuleId(ruleId);
      resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
    },
    [setCloneRuleId],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<EvaluatorsRule>(),
      ...convertColumnDataToColumn<EvaluatorsRule, EvaluatorsRule>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      {
        accessorKey: "rule_logs",
        header: "",
        cell: RuleLogsCell,
        size: 110,
        enableResizing: false,
        enableHiding: false,
        enableSorting: false,
      } as ColumnDef<EvaluatorsRule>,
      generateActionsColumDef<EvaluatorsRule>({
        cell: (props) => (
          <RuleRowActionsCell
            {...props}
            openEditDialog={handleOpenEditDialog}
            openCloneDialog={handleOpenCloneDialog}
          />
        ),
      }),
    ];
  }, [
    columnsOrder,
    selectedColumns,
    sortableBy,
    handleOpenEditDialog,
    handleOpenCloneDialog,
  ]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      enabledMultiSorting: false,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [sortedColumns, setSortedColumns],
  );

  const handleNewRuleClick = useCallback(() => {
    setOpenDialogForCreate(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleCloseDialog = useCallback(
    (open: boolean) => {
      setOpenDialogForCreate(open);
      if (!open) {
        setEditRuleId(undefined);
        setCloneRuleId(undefined);
      }
    },
    [setEditRuleId, setCloneRuleId],
  );

  // Filter out "type" (Scope), "enabled" (Status), "sampling_rate", and "projects" from filter options
  // Note: projects filtering is not supported by backend (see OPIK-3446)
  const filterableColumns = useMemo(
    () =>
      DEFAULT_COLUMNS.filter(
        (col) =>
          col.id !== "type" &&
          col.id !== "enabled" &&
          col.id !== "sampling_rate" &&
          col.id !== "projects",
      ),
    [],
  );

  if (isPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <>
        <NoRulesPage openModal={handleNewRuleClick} Wrapper={NoDataPage} />
        <AddEditRuleDialog
          key={resetDialogKeyRef.current}
          open={isDialogOpen}
          setOpen={handleCloseDialog}
          rule={dialogRule}
          mode={dialogMode}
        />
      </>
    );
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          Online evaluation
        </h1>
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_online_evaluation]}
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <FiltersButton
            columns={filterableColumns}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          ></FiltersButton>
        </div>
        <div className="flex items-center gap-2">
          <RulesActionsPanel rules={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" size="sm" onClick={handleNewRuleClick}>
            Create new rule
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
        ></DataTablePagination>
      </div>
      <AddEditRuleDialog
        key={resetDialogKeyRef.current}
        open={isDialogOpen}
        setOpen={handleCloseDialog}
        rule={dialogRule}
        mode={dialogMode}
      />
    </div>
  );
};

export default OnlineEvaluationPage;
