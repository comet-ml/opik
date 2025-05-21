import React, { useCallback, useMemo, useRef, useState } from "react";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import {
  ColumnDef,
  ColumnPinningState,
  RowSelectionState,
} from "@tanstack/react-table";

import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { EvaluatorsRule } from "@/types/automations";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
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
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
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

const getRowId = (d: EvaluatorsRule) => d.id;

const DEFAULT_COLUMNS: ColumnData<EvaluatorsRule>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
    sortable: true,
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
  {
    id: "sampling_rate",
    label: "Sampling rate",
    type: COLUMN_TYPE.number,
  },
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "last_updated_at",
  "created_by",
  "created_at",
  "sampling_rate",
];

const SELECTED_COLUMNS_KEY = "project-rules-selected-columns";
const COLUMNS_WIDTH_KEY = "project-rules-columns-width";
const COLUMNS_ORDER_KEY = "project-rules-columns-order";
const PAGINATION_SIZE_KEY = "project-rules-pagination-size";

type RulesTabProps = {
  projectId: string;
};

export const RulesTab: React.FC<RulesTabProps> = ({ projectId }) => {
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
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

  const { data, isPending } = useRulesList(
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

  const selectedRows: EvaluatorsRule[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<EvaluatorsRule>(),
      mapColumnDataFields<EvaluatorsRule, EvaluatorsRule>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
      }),
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
      generateActionsColumDef({
        cell: RuleRowActionsCell,
      }),
    ];
  }, [columnsOrder, selectedColumns]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewRuleClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <>
        <NoRulesPage
          openModal={handleNewRuleClick}
          Wrapper={NoDataPage}
          height={188}
          className="px-6"
        />
        <AddEditRuleDialog
          key={resetDialogKeyRef.current}
          open={openDialog}
          projectId={projectId}
          setOpen={setOpenDialog}
        />
      </>
    );
  }

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
          <Separator orientation="vertical" className="mx-1 h-4" />
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
      <AddEditRuleDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        projectId={projectId}
        setOpen={setOpenDialog}
      />
    </>
  );
};

export default RulesTab;
