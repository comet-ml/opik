import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import capitalize from "lodash/capitalize";

import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import FeedbackDefinitionsValueCell from "@/components/pages/FeedbackDefinitionsPage/FeedbackDefinitionsValueCell";
import FeedbackDefinitionsRowActionsCell from "@/components/pages/FeedbackDefinitionsPage/FeedbackDefinitionsRowActionsCell";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TagCell from "@/components/shared/DataTableCells/TagCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { formatDate } from "@/lib/date";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";

const SELECTED_COLUMNS_KEY = "feedback-definitions-selected-columns";
const COLUMNS_WIDTH_KEY = "feedback-definitions-columns-width";
const COLUMNS_ORDER_KEY = "feedback-definitions-columns-order";

export const DEFAULT_COLUMNS: ColumnData<FeedbackDefinition>[] = [
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
  },
  {
    id: "type",
    label: "Type",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => capitalize(row.type),
    cell: TagCell as never,
  },
  {
    id: "values",
    label: "Values",
    type: COLUMN_TYPE.string,
    cell: FeedbackDefinitionsValueCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = ["name", "type", "values"];

const FeedbackDefinitionsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const newFeedbackDefinitionDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);
  const { data, isPending } = useFeedbackDefinitionsList(
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

  const feedbackDefinitions = data?.content ?? [];
  const total = data?.total ?? 0;
  const noData = !search;
  const noDataText = noData
    ? "There are no feedback definitions yet"
    : "No search results";

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
    const retVal = convertColumnDataToColumn<
      FeedbackDefinition,
      FeedbackDefinition
    >(DEFAULT_COLUMNS, {
      columnsOrder,
      columnsWidth,
      selectedColumns,
    });

    retVal.push({
      id: "actions",
      enableHiding: false,
      cell: FeedbackDefinitionsRowActionsCell,
      size: 48,
      enableResizing: false,
    });

    return retVal;
  }, [columnsOrder, columnsWidth, selectedColumns]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  const handleNewFeedbackDefinitionClick = useCallback(() => {
    setOpenDialog(true);
    newFeedbackDefinitionDialogKeyRef.current =
      newFeedbackDefinitionDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">Feedback definitions</h1>
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
          <Button variant="default" onClick={handleNewFeedbackDefinitionClick}>
            Create new feedback definition
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={feedbackDefinitions}
        resizeConfig={resizeConfig}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewFeedbackDefinitionClick}>
                Create new feedback definition
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
      <AddEditFeedbackDefinitionDialog
        key={newFeedbackDefinitionDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default FeedbackDefinitionsPage;
