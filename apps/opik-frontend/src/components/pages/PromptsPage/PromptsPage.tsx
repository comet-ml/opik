import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import Loader from "@/components/shared/Loader/Loader";
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
import useLocalStorageState from "use-local-storage-state";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import usePromptsList from "@/api/prompts/usePromptsList";
import { Prompt } from "@/types/prompts";
import { PromptRowActionsCell } from "@/components/pages/PromptsPage/PromptRowActionsCell";
import AddEditPromptDialog from "@/components/pages/PromptsPage/AddEditPromptDialog";
import PromptsActionsPanel from "@/components/pages/PromptsPage/PromptsActionsPanel";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

export const getRowId = (p: Prompt) => p.id;

const SELECTED_COLUMNS_KEY = "prompts-selected-columns";
const COLUMNS_WIDTH_KEY = "prompts-columns-width";
const COLUMNS_ORDER_KEY = "prompts-columns-order";
const PAGINATION_SIZE_KEY = "prompts-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Prompt>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "version_count",
    label: "Versions",
    type: COLUMN_TYPE.number,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "version_count",
  "last_updated_at",
  "description",
];

const PromptsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = usePromptsList(
    {
      workspaceName,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const prompts = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noData = !search;
  const noDataText = noData ? "There are no prompts yet" : "No search results";

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

  const selectedRows: Prompt[] = useMemo(() => {
    return prompts.filter((row) => rowSelection[row.id]);
  }, [rowSelection, prompts]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Prompt>(),
      mapColumnDataFields<Prompt, Prompt>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.prompt,
        },
      }),
      ...convertColumnDataToColumn<Prompt, Prompt>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
      }),
      generateActionsColumDef({
        cell: PromptRowActionsCell,
      }),
    ];
  }, [selectedColumns, columnsOrder]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (row: Prompt) => {
      navigate({
        to: "/$workspaceName/prompts/$promptId",
        params: {
          promptId: row.id,
          workspaceName,
        },
      });
    },
    [navigate, workspaceName],
  );

  const handleNewPromptClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Prompt library</h1>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        ></SearchInput>
        <div className="flex items-center gap-2">
          <PromptsActionsPanel prompts={selectedRows} />
          <Separator orientation="vertical" className="mx-1 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
          <Button variant="default" size="sm" onClick={handleNewPromptClick}>
            Create new prompt
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={prompts}
        onRowClick={handleRowClick}
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
              <Button variant="link" onClick={handleNewPromptClick}>
                Create new prompt
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
        />
      </div>
      <AddEditPromptDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default PromptsPage;
