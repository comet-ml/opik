import React, { useCallback, useMemo, useRef, useState } from "react";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import {
  ColumnDef,
  ColumnPinningState,
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
import DataTableStateHandler from "@/components/shared/DataTableStateHandler/DataTableStateHandler";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import StatusCell from "@/components/shared/DataTableCells/StatusCell";
import { formatDate } from "@/lib/date";
import useRulesList from "@/api/automations/useRulesList";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import NoRulesPage from "@/components/pages-shared/automations/NoRulesPage";
import AddEditRuleDialog from "@/components/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog";
import RulesActionsPanel from "@/components/pages-shared/automations/RulesActionsPanel";
import RuleRowActionsCell from "@/components/pages-shared/automations/RuleRowActionsCell";
import RuleLogsCell from "@/components/pages-shared/automations/RuleLogsCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
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
    id: "scope",
    label: "Scope",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => capitalizeFirstLetter(getUIRuleScope(row.type)),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
    sortable: true,
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
    sortable: true,
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
  "enabled",
  "sampling_rate",
  "scope",
  "last_updated_at",
];

const SELECTED_COLUMNS_KEY = "project-rules-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "project-rules-columns-width";
const COLUMNS_ORDER_KEY = "project-rules-columns-order";
const PAGINATION_SIZE_KEY = "project-rules-pagination-size";

type RulesTabProps = {
  projectId: string;
};

export const RulesTab: React.FC<RulesTabProps> = ({ projectId }) => {
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
      projectId,
      page: page as number,
      size: size as number,
      search: search as string,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const noData = !search;
  const noDataText = noData ? `There are no rules yet` : "No search results";

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

  const showEmptyState =
    !isPending && noData && rows.length === 0 && page === 1;

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 py-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
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
      </PageBodyStickyContainer>
      <DataTableStateHandler
        isLoading={isPending}
        isEmpty={showEmptyState}
        emptyState={<NoRulesPage Wrapper={NoDataPage} className="px-6" />}
      >
        <DataTable
          columns={columns}
          data={rows}
          resizeConfig={resizeConfig}
          selectionConfig={{
            rowSelection,
            setRowSelection,
          }}
          getRowId={getRowId}
          columnPinning={DEFAULT_COLUMN_PINNING}
          noData={<DataTableNoData title={noDataText} />}
          TableWrapper={PageBodyStickyTableWrapper}
          stickyHeader
          showLoadingOverlay={isPlaceholderData && isFetching}
        />
        <PageBodyStickyContainer
          className="py-4"
          direction="horizontal"
          limitWidth
        >
          <DataTablePagination
            page={page as number}
            pageChange={setPage}
            size={size as number}
            sizeChange={setSize}
            total={data?.total ?? 0}
          ></DataTablePagination>
        </PageBodyStickyContainer>
      </DataTableStateHandler>
      <AddEditRuleDialog
        key={resetDialogKeyRef.current}
        open={isDialogOpen}
        projectId={projectId}
        setOpen={handleCloseDialog}
        rule={dialogRule}
        mode={dialogMode}
      />
    </>
  );
};

export default RulesTab;
