import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { Dataset } from "@/types/datasets";
import Loader from "@/components/shared/Loader/Loader";
import AddDatasetDialog from "@/components/pages/DatasetsPage/AddDatasetDialog";
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
import useLocalStorageState from "use-local-storage-state";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

export const getRowId = (d: Dataset) => d.id;

const SELECTED_COLUMNS_KEY = "datasets-selected-columns";
const COLUMNS_WIDTH_KEY = "datasets-columns-width";
const COLUMNS_ORDER_KEY = "datasets-columns-order";

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
    id: "most_recent_experiment_at",
    label: "Most recent experiment",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.most_recent_experiment_at),
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

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = useDatasetsList(
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

  const datasets = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noData = !search;
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
        size: columnsWidth[COLUMN_NAME_ID],
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.dataset,
        },
      }),
      ...convertColumnDataToColumn<Dataset, Dataset>(DEFAULT_COLUMNS, {
        columnsOrder,
        columnsWidth,
        selectedColumns,
      }),
      generateActionsColumDef({
        cell: DatasetRowActionsCell,
      }),
    ];
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

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Datasets</h1>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
        ></SearchInput>
        <div className="flex items-center gap-2">
          <DatasetsActionsPanel datasets={selectedRows} />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="default" onClick={handleNewDatasetClick}>
            Create new dataset
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={datasets}
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
      <AddDatasetDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default DatasetsPage;
