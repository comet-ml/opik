import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { PlusIcon } from "lucide-react";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/lib/utils";
import emptyPromptLibraryLightUrl from "/images/empty-prompt-library-light.svg";
import emptyPromptLibraryDarkUrl from "/images/empty-prompt-library-dark.svg";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/shared/DataTableCells/IdCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import TagCell from "@/shared/DataTableCells/TagCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import useProjectPromptsList from "@/api/prompts/useProjectPromptsList";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { PromptRowActionsCell } from "@/v2/pages/PromptsPage/PromptRowActionsCell";
import AddEditPromptDialog from "@/v2/pages/PromptsPage/AddEditPromptDialog";
import PromptsActionsPanel from "@/v2/pages/PromptsPage/PromptsActionsPanel";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { usePermissions } from "@/contexts/PermissionsContext";

export const getRowId = (p: Prompt) => p.id;

const SELECTED_COLUMNS_KEY = "prompts-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "prompts-columns-width";
const COLUMNS_ORDER_KEY = "prompts-columns-order";
const COLUMNS_SORT_KEY = "prompts-columns-sort";
const PAGINATION_SIZE_KEY = "prompts-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Prompt>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    sortable: true,
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "template_structure",
    label: "Type",
    type: COLUMN_TYPE.category,
    cell: TagCell as never,
    size: 80,
    accessorFn: (row) => {
      const structure =
        row.template_structure || PROMPT_TEMPLATE_STRUCTURE.TEXT;
      return structure === PROMPT_TEMPLATE_STRUCTURE.CHAT
        ? PROMPT_TEMPLATE_STRUCTURE.CHAT
        : PROMPT_TEMPLATE_STRUCTURE.TEXT;
    },
    customMeta: { colored: false },
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "version_count",
    label: "Versions",
    type: COLUMN_TYPE.number,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const FILTER_COLUMNS: ColumnData<Prompt>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: "description",
    label: "Description",
    type: COLUMN_TYPE.string,
  },
  {
    id: "template_structure",
    label: "Type",
    type: COLUMN_TYPE.string,
  },
  {
    id: "version_count",
    label: "Versions",
    type: COLUMN_TYPE.number,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "version_count",
  "template_structure",
  "last_updated_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  "version_count",
  "template_structure",
  "last_updated_at",
  "tags",
  "description",
  "created_at",
  "created_by",
];

const PromptsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam(`filters`, JsonParam, {
    updateType: "replaceIn",
  });

  const {
    permissions: { canCreatePrompts, canDeletePrompts },
  } = usePermissions();

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: `sorting`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [page, setPage] = useState(1);
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending, isPlaceholderData, isFetching } =
    useProjectPromptsList(
      {
        projectId: activeProjectId!,
        filters,
        sorting: sortedColumns,
        search: search!,
        page,
        size,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const prompts = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? ["tags"],
    [data?.sortable_by],
  );
  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const noDataText = noData ? "There are no prompts yet" : "No search results";

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
      defaultValue: DEFAULT_COLUMNS_ORDER,
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
      ...convertColumnDataToColumn<Prompt, Prompt>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      generateActionsColumDef({
        cell: PromptRowActionsCell,
      }),
    ];
  }, [sortableBy, columnsOrder, selectedColumns]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

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
        to: "/$workspaceName/projects/$projectId/prompts/$promptId",
        params: {
          promptId: row.id,
          workspaceName,
          projectId: activeProjectId!,
        },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );

  const handleNewPromptClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (isPending) {
    return <Loader />;
  }

  const isEmpty = noData && prompts.length === 0;

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-4 flex min-h-7 items-center justify-between">
        <h1 className="comet-body-accented truncate break-words">
          Prompt library
        </h1>
        {canCreatePrompts && (
          <Button variant="default" size="xs" onClick={handleNewPromptClick}>
            <PlusIcon className="mr-1 size-4" />
            Create prompt
          </Button>
        )}
      </div>
      {isEmpty ? (
        <PageEmptyState
          lightImageUrl={emptyPromptLibraryLightUrl}
          darkImageUrl={emptyPromptLibraryDarkUrl}
          title="No prompts yet"
          description={
            "Create and manage prompts in one place.\nVersion them, improve and reuse, and keep your workflows consistent."
          }
          primaryActionLabel="Create your first prompt"
          onPrimaryAction={handleNewPromptClick}
          docsUrl={buildDocsUrl("/prompt_engineering/prompt_management")}
        />
      ) : (
        <>
          <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
            <div className="flex items-center gap-2">
              <SearchInput
                searchText={search!}
                setSearchText={setSearch}
                placeholder="Search by name"
                className="w-[320px]"
                dimension="sm"
              ></SearchInput>
              <FiltersButton
                columns={FILTER_COLUMNS}
                filters={filters}
                onChange={setFilters}
                layout="icon"
              />
            </div>
            <div className="flex items-center gap-2">
              {canDeletePrompts && (
                <>
                  <PromptsActionsPanel prompts={selectedRows} />
                  <Separator orientation="vertical" className="mx-2 h-4" />
                </>
              )}
              <ColumnsButton
                columns={DEFAULT_COLUMNS}
                selectedColumns={selectedColumns}
                onSelectionChange={setSelectedColumns}
                order={columnsOrder}
                onOrderChange={setColumnsOrder}
              />
            </div>
          </div>
          <DataTable
            columns={columns}
            data={prompts}
            onRowClick={handleRowClick}
            sortConfig={sortConfig}
            resizeConfig={resizeConfig}
            selectionConfig={{
              rowSelection,
              setRowSelection,
            }}
            getRowId={getRowId}
            columnPinning={DEFAULT_COLUMN_PINNING}
            noData={
              <DataTableNoData title={noDataText}>
                {noData && canCreatePrompts && (
                  <Button variant="link" onClick={handleNewPromptClick}>
                    Create prompt
                  </Button>
                )}
              </DataTableNoData>
            }
            showLoadingOverlay={isPlaceholderData && isFetching}
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
        </>
      )}
      <AddEditPromptDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default PromptsPage;
