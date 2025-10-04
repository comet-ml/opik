import React, { useCallback, useMemo, useState } from "react";
import { ColumnSort, RowSelectionState } from "@tanstack/react-table";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import Loader from "@/components/shared/Loader/Loader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";

const getRowId = (d: Experiment) => d.id;

const DEFAULT_COLUMNS: ColumnData<Experiment>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TextCell,
  },
  {
    id: "experiment_type",
    label: "Type",
    type: COLUMN_TYPE.string,
    cell: TextCell,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: ({ getValue }) => {
      const value = getValue() as string;
      return <span className="text-muted-foreground">{formatDate(value)}</span>;
    },
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: "Comments",
    type: COLUMN_TYPE.number,
    cell: CommentsCell as never,
  },
];

const SELECTED_COLUMNS_KEY = "dataset-experiments-selected-columns";
const PAGINATION_SIZE_KEY = "dataset-experiments-pagination-size";
const COLUMNS_SORT_KEY = "dataset-experiments-columns-sort";

const DatasetExperimentsPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const datasetId = useDatasetIdFromURL();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 10, setSize] = useLocalStorageState(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_COLUMNS.map((column) => column.id),
    },
  );

  const [sorting, setSorting] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [],
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = useExperimentsList(
    {
      datasetId,
      workspaceName,
      search,
      page,
      size,
      types: [EXPERIMENT_TYPE.REGULAR], // Filter to regular experiments
      sorting: sorting?.[0]
        ? {
            id: sorting[0].id,
            desc: sorting[0].desc,
          }
        : undefined,
    },
    {
      placeholderData: keepPreviousData,
      refetchOnWindowFocus: false,
    },
  );

  const experiments = data?.content ?? [];
  const total = data?.total ?? 0;
  const noDataText = search
    ? "No experiments found for your search"
    : "No experiments found for this dataset";

  const columns = useMemo(() => {
    const retVal = [
      generateSelectColumDef<Experiment>(),
      ...convertColumnDataToColumn<Experiment, Experiment>(
        DEFAULT_COLUMNS,
        {
          columnsData: DEFAULT_COLUMNS,
          selectedColumns,
        },
      ),
    ];

    return retVal;
  }, [selectedColumns]);

  const handleSearch = useCallback(
    (searchString: string) => {
      setPage(1);
      setSearch(searchString);
    },
    [setPage, setSearch],
  );

  const handleNewPage = useCallback(
    (newPage: number) => setPage(newPage),
    [setPage],
  );

  const handleNewPageSize = useCallback(
    (newPageSize: number) => {
      setSize(newPageSize);
      setPage(1);
    },
    [setSize, setPage],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={handleSearch}
          placeholder="Search by experiment name"
          className="w-[320px]"
        />
        <div className="flex items-center gap-2">
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={[]}
            onOrderChange={() => {}}
          />
        </div>
      </div>

      <div className="border rounded-md">
        <DataTable
          columns={columns}
          data={experiments}
          onRowSelectionChange={setRowSelection}
          rowSelection={rowSelection}
          sorting={sorting}
          setSorting={setSorting}
          getRowId={getRowId}
          noData={
            <DataTableNoData title={noDataText}>
              {!search && (
                <p className="text-muted-foreground">
                  Experiments linked to this dataset will appear here.
                </p>
              )}
            </DataTableNoData>
          }
        />
      </div>

      <div className="py-4">
        <DataTablePagination
          page={page}
          pageCount={Math.ceil(total / size)}
          total={total}
          pageSize={size}
          onPageChange={handleNewPage}
          onPageSizeChange={handleNewPageSize}
        />
      </div>
    </div>
  );
};

export default DatasetExperimentsPage;