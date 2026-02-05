import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
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
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import usePromptsList from "@/api/prompts/usePromptsList";
import { Prompt, PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { PromptRowActionsCell } from "@/components/pages/PromptsPage/PromptRowActionsCell";
import AddEditPromptDialog from "@/components/pages/PromptsPage/AddEditPromptDialog";
import PromptsActionsPanel from "@/components/pages/PromptsPage/PromptsActionsPanel";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import BlueprintTimeline from "./BlueprintTimeline";
import useBlueprintForProject from "@/api/blueprints/useBlueprintForProject";
import useBlueprintHistory from "@/api/blueprints/useBlueprintHistory";
import useMigrateToBlueprint from "@/api/blueprints/useMigrateToBlueprint";
import EnvironmentBadge from "./EnvironmentBadge";

export const getRowId = (p: Prompt) => p.id;

const SELECTED_COLUMNS_KEY = "prompts-selected-columns";
const COLUMNS_WIDTH_KEY = "prompts-columns-width";
const COLUMNS_ORDER_KEY = "prompts-columns-order";
const COLUMNS_SORT_KEY = "prompts-columns-sort";
const PAGINATION_SIZE_KEY = "prompts-pagination-size";

export const DEFAULT_COLUMNS: ColumnData<Prompt>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "template_structure",
    label: "Type",
    type: COLUMN_TYPE.string,
    size: 80,
    accessorFn: (row) => {
      const structure =
        row.template_structure || PROMPT_TEMPLATE_STRUCTURE.TEXT;
      return structure === PROMPT_TEMPLATE_STRUCTURE.CHAT
        ? PROMPT_TEMPLATE_STRUCTURE.CHAT
        : PROMPT_TEMPLATE_STRUCTURE.TEXT;
    },
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
    accessorFn: (row) => formatDate(row.last_updated_at),
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
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "template_structure",
  "description",
  "version_count",
  "last_updated_at",
];

const PromptsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const [viewMode, setViewMode] = useState<"prompts" | "timeline">("timeline");

  // Blueprint data
  const projectId = "default";
  const { data: blueprint, isLoading: blueprintLoading } =
    useBlueprintForProject({ projectId });
  const { data: history, isLoading: historyLoading } = useBlueprintHistory({
    blueprintId: blueprint?.id || "",
    enabled: !!blueprint?.id,
  });
  const migrateMutation = useMigrateToBlueprint();

  const handleMigrate = async () => {
    await migrateMutation.mutateAsync({ projectId });
  };

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam(`filters`, JsonParam, {
    updateType: "replaceIn",
  });

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

  const { data, isPending } = usePromptsList(
    {
      workspaceName,
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
        sortable: isColumnSortable(COLUMN_NAME_ID, sortableBy),
      }),
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
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Blueprints</h1>
        {history && (
          <div className="flex items-center gap-3 text-sm">
            {Object.entries(history.pointers)
              .sort(([a], [b]) => {
                // Sort: latest first, then prod, then others alphabetically
                if (a === "latest") return -1;
                if (b === "latest") return 1;
                if (a === "prod") return -1;
                if (b === "prod") return 1;
                return a.localeCompare(b);
              })
              .map(([env, version]) => (
                <div key={env} className="flex items-center gap-1.5">
                  <EnvironmentBadge env={env} />
                  <span className="font-mono">v{version}</span>
                </div>
              ))}
          </div>
        )}
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_prompt_library]}
      />

      {/* View mode toggle */}
      <div className="mb-4 flex items-center gap-2 border-b">
        <button
          onClick={() => setViewMode("timeline")}
          className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
            viewMode === "timeline"
              ? "border-primary text-primary"
              : "border-transparent text-muted-slate hover:text-foreground"
          }`}
        >
          Deployment History
        </button>
        <button
          onClick={() => setViewMode("prompts")}
          className={`border-b-2 px-4 py-2 text-sm font-medium transition-colors ${
            viewMode === "prompts"
              ? "border-primary text-primary"
              : "border-transparent text-muted-slate hover:text-foreground"
          }`}
        >
          Prompts
        </button>
      </div>

      {viewMode === "timeline" ? (
        /* Timeline View */
        <div className="rounded-lg border bg-background p-6">
          {blueprintLoading || historyLoading ? (
            <Loader />
          ) : !blueprint ? (
            <div className="flex flex-col items-center justify-center gap-4 py-12">
              <p className="text-muted-slate">
                No blueprint found for this project.
              </p>
              <Button
                onClick={handleMigrate}
                disabled={migrateMutation.isPending}
              >
                {migrateMutation.isPending
                  ? "Migrating..."
                  : "Initialize Blueprint"}
              </Button>
            </div>
          ) : history ? (
            <BlueprintTimeline blueprintId={blueprint.id} history={history} />
          ) : null}
        </div>
      ) : (
        /* Prompts Table View */
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
              <div className="flex h-7 items-center rounded-md bg-primary-100 px-2 text-xs font-medium text-primary">
                Prompts only
              </div>
              <FiltersButton
                columns={FILTER_COLUMNS}
                filters={filters}
                onChange={setFilters}
                layout="icon"
              />
            </div>
            <div className="flex items-center gap-2">
              <PromptsActionsPanel prompts={selectedRows} />
              <Separator orientation="vertical" className="mx-2 h-4" />
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
