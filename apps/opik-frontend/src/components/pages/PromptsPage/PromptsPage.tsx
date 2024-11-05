import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { formatDate } from "@/lib/date";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import useLocalStorageState from "use-local-storage-state";
import { convertColumnDataToColumn } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import usePromptsList from "@/api/prompts/usePromptsList";
import { Prompt } from "@/types/prompts";
import { PromptRowActionsCell } from "@/components/pages/PromptsPage/PromptRowActionsCell";
import AddPromptDialog from "@/components/pages/PromptsPage/AddPromptDialog";
import TagNameCell from "@/components/pages/PromptsPage/TagNameCell";
import { useNavigate } from "@tanstack/react-router";

const SELECTED_COLUMNS_KEY = "prompts-selected-columns";
const COLUMNS_WIDTH_KEY = "prompts-columns-width";
const COLUMNS_ORDER_KEY = "prompts-columns-order";

// ALEX
// ASK ABOUT PUTTING IT INTO A DIFFERENT HOOK FOR COLUMN STATES
// ADD A key to the sidebar

export const DEFAULT_COLUMNS: ColumnData<Prompt>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TagNameCell as never,
  },
  {
    id: "versions_count",
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
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "name",
  "versions_count",
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
  const [size, setSize] = useState(10);
  const { data, isPending } = usePromptsList(
    {
      workspaceName,
      search,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const prompts = data?.content ?? [];
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

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<Prompt, Prompt>(DEFAULT_COLUMNS, {
      columnsOrder,
      columnsWidth,
      selectedColumns,
    });

    retVal.push({
      id: "actions",
      enableHiding: false,
      cell: PromptRowActionsCell,
      size: 48,
      enableResizing: false,
    });

    return retVal;
  }, [selectedColumns, columnsWidth, columnsOrder]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  const handleNewDatasetClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback((prompt: Prompt) => {
    navigate({
      to: "/$workspaceName/prompts/$promptId",
      params: {
        promptId: prompt.id,
        workspaceName,
      },
    });
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
        ></SearchInput>
        <div className="flex items-center gap-2">
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" onClick={handleNewDatasetClick}>
            Create new prompt
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={prompts}
        onRowClick={handleRowClick}
        resizeConfig={resizeConfig}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewDatasetClick}>
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
        ></DataTablePagination>
      </div>
      <AddPromptDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default PromptsPage;
