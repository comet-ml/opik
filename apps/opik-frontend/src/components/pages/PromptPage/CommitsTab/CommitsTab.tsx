import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnSort, RowSelectionState } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, useQueryParam } from "use-query-params";
import get from "lodash/get";
import isObject from "lodash/isObject";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import Loader from "@/components/shared/Loader/Loader";
import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { formatDate } from "@/lib/date";
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import CommitsActionsPanel from "@/components/pages/PromptPage/CommitsTab/CommitsActionsPanel";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Separator } from "@/components/ui/separator";
import PromptVersionsMetadataAutocomplete from "@/components/pages/PromptPage/CommitsTab/PromptVersionsMetadataAutocomplete";

export const getRowId = (p: PromptVersion) => p.id;

interface CommitsTabInterface {
  prompt?: PromptWithLatestVersion;
}

const PAGINATION_SIZE_KEY = "prompt-commits-pagination-size";
const COLUMNS_WIDTH_KEY = "prompt-commits-columns-width";
const SELECTED_COLUMNS_KEY = "prompt-commits-selected-columns";
const COLUMNS_ORDER_KEY = "prompt-commits-columns-order";
const COLUMNS_SORT_KEY = "prompt-commits-columns-sort";

export const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "created_at",
    desc: true,
  },
];

export const DEFAULT_COLUMNS: ColumnData<PromptVersion>[] = [
  {
    id: "template",
    label: "Prompt",
    type: COLUMN_TYPE.dictionary,
    cell: CodeCell as never,
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.metadata)
        ? JSON.stringify(row.metadata, null, 2)
        : row.metadata,
    cell: CodeCell as never,
  },
  {
    id: "change_description",
    label: "Commit message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "tags",
    label: "Version tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    accessorFn: (row) => row.tags || [],
    cell: ListCell as never,
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "template",
  "metadata",
  "change_description",
  "tags",
  "created_at",
  "created_by",
];

export const FILTER_COLUMNS: ColumnData<PromptVersion>[] = [
  {
    id: "commit",
    label: "Prompt commit",
    type: COLUMN_TYPE.string,
  },
  {
    id: "template",
    label: "Prompt",
    type: COLUMN_TYPE.string,
  },
  {
    id: "change_description",
    label: "Commit message",
    type: COLUMN_TYPE.string,
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: "Version tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

const CommitsTab = ({ prompt }: CommitsTabInterface) => {
  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [searchText, setSearchText] = useState<string>("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

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

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const { data, isPending } = usePromptVersionsById(
    {
      promptId: prompt?.id || "",
      page,
      size,
      sorting: sortedColumns,
      filters,
      search: searchText || undefined,
    },
    {
      enabled: !!prompt?.id,
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const versions = useMemo(() => data?.content ?? [], [data?.content]);
  const noDataText = "There are no commits yet";

  const columns = useMemo(() => {
    // Get sortable columns dynamically from backend API response
    const sortableColumns = data?.sortable_by || [];

    return [
      generateSelectColumDef<PromptVersion>(),
      mapColumnDataFields<PromptVersion, PromptVersion>({
        id: "commit",
        label: "Prompt commit",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "commit",
          idKey: "prompt_id",
          resource: RESOURCE_TYPE.prompt,
          getSearch: (data: PromptVersion) => ({
            activeVersionId: get(data, "id", null),
          }),
        },
        explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_a_prompt_commit],
        sortable: isColumnSortable("commit", sortableColumns),
      }),
      ...convertColumnDataToColumn<PromptVersion, PromptVersion>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns,
        },
      ),
    ];
  }, [columnsOrder, selectedColumns, data?.sortable_by]);

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
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [sortedColumns, setSortedColumns],
  );

  const selectedRows: PromptVersion[] = useMemo(() => {
    return versions.filter((row) => rowSelection[row.id]);
  }, [rowSelection, versions]);

  const handleSearchTextChange = useCallback(
    (value: string) => {
      setSearchText(value);
      // Reset to page 1 when search changes
      if (page !== 1) {
        setPage(1);
      }
    },
    [page, setPage],
  );

  if (isPending) {
    return <Loader />;
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
            searchText={searchText}
            setSearchText={handleSearchTextChange}
            placeholder="Search in prompt or commit message"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={FILTER_COLUMNS}
            filters={filters}
            onChange={setFilters}
            config={{
              rowsMap: {
                metadata: {
                  keyComponent:
                    PromptVersionsMetadataAutocomplete as React.FC<unknown> & {
                      placeholder: string;
                      value: string;
                      onValueChange: (value: string) => void;
                    },
                  keyComponentProps: {
                    prompt,
                    placeholder: "key",
                  },
                },
              },
            }}
          />
        </div>
        <div className="flex items-center gap-2">
          <CommitsActionsPanel versions={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={versions}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        noData={<DataTableNoData title={noDataText} />}
        TableBody={DataTableVirtualBody}
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
    </>
  );
};

export default CommitsTab;
