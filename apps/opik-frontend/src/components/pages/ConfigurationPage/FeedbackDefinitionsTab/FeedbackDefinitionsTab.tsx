import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import capitalize from "lodash/capitalize";

import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import AddEditFeedbackDefinitionDialog from "@/components/shared/AddEditFeedbackDefinitionDialog/AddEditFeedbackDefinitionDialog";
import FeedbackDefinitionsValueCell from "@/components/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsValueCell";
import FeedbackDefinitionsRowActionsCell from "@/components/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsRowActionsCell";
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
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { formatDate } from "@/lib/date";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { Separator } from "@/components/ui/separator";
import FeedbackDefinitionsActionsPanel from "@/components/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsActionsPanel";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";

export const getRowId = (f: FeedbackDefinition) => f.id;

const SELECTED_COLUMNS_KEY = "feedback-definitions-selected-columns";
const COLUMNS_WIDTH_KEY = "feedback-definitions-columns-width";
const COLUMNS_ORDER_KEY = "feedback-definitions-columns-order";
const PAGINATION_SIZE_KEY = "feedback-definitions-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<FeedbackDefinition>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
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

export const DEFAULT_SELECTED_COLUMNS: string[] = ["type", "values"];

const FeedbackDefinitionsTab: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const newFeedbackDefinitionDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = useFeedbackDefinitionsList(
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

  const feedbackDefinitions = useMemo(
    () => data?.content ?? [],
    [data?.content],
  );
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

  const selectedRows: FeedbackDefinition[] = useMemo(() => {
    return feedbackDefinitions.filter((row) => rowSelection[row.id]);
  }, [rowSelection, feedbackDefinitions]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<FeedbackDefinition>(),
      mapColumnDataFields<FeedbackDefinition, FeedbackDefinition>({
        id: "name",
        label: "Feedback score",
        type: COLUMN_TYPE.numberDictionary,
        cell: FeedbackScoreNameCell as never,
      }),
      ...convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      generateActionsColumDef({
        cell: FeedbackDefinitionsRowActionsCell,
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

  const handleNewFeedbackDefinitionClick = useCallback(() => {
    setOpenDialog(true);
    newFeedbackDefinitionDialogKeyRef.current =
      newFeedbackDefinitionDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        ></SearchInput>

        <div className="flex items-center gap-2">
          <FeedbackDefinitionsActionsPanel feedbackDefinitions={selectedRows} />
          <Separator orientation="vertical" className="mx-1 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button
            variant="default"
            size="sm"
            onClick={handleNewFeedbackDefinitionClick}
          >
            Create new feedback definition
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={feedbackDefinitions}
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

export default FeedbackDefinitionsTab;
