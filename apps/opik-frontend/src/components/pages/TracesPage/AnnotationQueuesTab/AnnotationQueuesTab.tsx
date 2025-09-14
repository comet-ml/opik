import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { ColumnSort } from "@tanstack/react-table";
import { Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FeedbackDefinitionListCell from "@/components/shared/DataTableCells/FeedbackDefinitionListCell";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { Filter } from "@/types/filters";
import { v7 as uuidv7 } from "uuid";

import {
  AnnotationQueue,
  AnnotationQueueScope,
} from "@/types/annotation-queues";
import useAppStore from "@/store/AppStore";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import CreateAnnotationQueueDialog from "../../AnnotationQueuesPage/CreateAnnotationQueueDialog";
import AnnotationQueuesActionsPanel from "../../AnnotationQueuesPage/AnnotationQueuesActionsPanel";
import { AnnotationQueueRowActionsCell } from "../../AnnotationQueuesPage/AnnotationQueueRowActionsCell";

// Import column constants from the standalone page to ensure consistency
import {
  COLUMN_INSTRUCTIONS,
  COLUMN_FEEDBACK_SCORES,
  COLUMN_SCOPE,
  COLUMN_ITEMS_COUNT,
  COLUMN_CREATED_AT,
  COLUMN_CREATED_BY,
  COLUMN_LAST_UPDATED_AT,
  COLUMN_REVIEWED_BY,
  DEFAULT_SELECTED_COLUMNS,
  DEFAULT_SORTING_COLUMNS,
} from "../../AnnotationQueuesPage/AnnotationQueuesPage";

const SELECTED_COLUMNS_KEY = "project-annotation-queues-selected-columns";
const COLUMNS_WIDTH_KEY = "project-annotation-queues-columns-width";
const COLUMNS_ORDER_KEY = "project-annotation-queues-columns-order";
const PAGINATION_SIZE_KEY = "project-annotation-queues-pagination-size";
const COLUMNS_SORT_KEY = "project-annotation-queues-columns-sort";

export const getRowId = (queue: AnnotationQueue) => queue.id;

type AnnotationQueuesTabProps = {
  projectId: string;
  projectName: string;
};

const AnnotationQueuesTab: React.FunctionComponent<
  AnnotationQueuesTabProps
> = ({ projectId, projectName: _projectName }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam(
    "annotation_queues_search",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [page = 1, setPage] = useQueryParam(
    "annotation_queues_page",
    NumberParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "annotation_queues_size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [rowSelection = {}, setRowSelection] = useQueryParam(
    "annotation_queues_selection",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

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

  const { data, isPending } = useAnnotationQueuesList(
    {
      workspaceName,
      search: search || "",
      page: 1, // Get all queues to filter by project on frontend
      size: 1000, // Get all queues to filter by project on frontend
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  // Filter annotation queues by current project
  const allQueues = useMemo(() => data?.content ?? [], [data?.content]);
  const projectFilteredQueues = useMemo(() => {
    return allQueues.filter((queue) => queue.project_id === projectId);
  }, [allQueues, projectId]);

  // Apply search filter
  const searchFilteredQueues = useMemo(() => {
    if (!search) return projectFilteredQueues;
    return projectFilteredQueues.filter(
      (queue) =>
        queue.name.toLowerCase().includes(search.toLowerCase()) ||
        queue.description?.toLowerCase().includes(search.toLowerCase()),
    );
  }, [projectFilteredQueues, search]);

  // Apply pagination to filtered results
  const paginatedQueues = useMemo(() => {
    const startIndex = (page! - 1) * size!;
    const endIndex = startIndex + size!;
    return searchFilteredQueues.slice(startIndex, endIndex);
  }, [searchFilteredQueues, page, size]);

  const annotationQueues = paginatedQueues;
  const total = searchFilteredQueues.length;
  const noData = !search;
  const noDataText = noData
    ? "There are no annotation queues yet"
    : "No search results";

  const selectedRows: AnnotationQueue[] = useMemo(() => {
    return annotationQueues.filter((queue) => rowSelection[queue.id]);
  }, [annotationQueues, rowSelection]);

  const columnsDef: ColumnData<AnnotationQueue>[] = useMemo(() => {
    return [
      // Shown by default columns
      {
        id: COLUMN_INSTRUCTIONS,
        label: "Instructions",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.instructions || "-",
      },
      {
        id: COLUMN_FEEDBACK_SCORES,
        label: "Feedback definitions",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => {
          if (
            row.feedback_definition_names &&
            row.feedback_definition_names.length > 0
          ) {
            return row.feedback_definition_names;
          }
          return [];
        },
        cell: FeedbackDefinitionListCell as never,
        customMeta: {
          getHoverCardName: (row: AnnotationQueue) => row.name,
        },
      },
      {
        id: COLUMN_LAST_UPDATED_AT,
        label: "Last updated",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.last_updated_at),
        sortable: true,
      },
      {
        id: COLUMN_SCOPE,
        label: "Scope",
        type: COLUMN_TYPE.string,
        accessorFn: (row) =>
          row.scope === AnnotationQueueScope.TRACE ? "Traces" : "Threads",
      },
      {
        id: COLUMN_ITEMS_COUNT,
        label: "Item count",
        type: COLUMN_TYPE.number,
        accessorFn: (row) => row.items_count || 0,
      },
      // Other hideable columns
      {
        id: COLUMN_CREATED_AT,
        label: "Created at",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
        sortable: true,
      },
      {
        id: COLUMN_CREATED_BY,
        label: "Created by",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.created_by || "-",
      },
      {
        id: COLUMN_REVIEWED_BY,
        label: "Reviewed by",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => {
          if (!row.reviewers || row.reviewers.length === 0) return "-";
          return row.reviewers.map((r) => r.username).join(", ");
        },
      },
    ];
  }, []);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<AnnotationQueue>(),
      // Can't be hidden: Name
      mapColumnDataFields<AnnotationQueue, AnnotationQueue>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        sortable: true,
      }),
      ...convertColumnDataToColumn<AnnotationQueue, AnnotationQueue>(
        columnsDef,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      // Can't be hidden: Actions
      generateActionsColumDef({
        cell: AnnotationQueueRowActionsCell,
      }),
    ];
  }, [columnsDef, selectedColumns, columnsOrder]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (queue: AnnotationQueue) => {
      // Create filter for annotation queue name
      const annotationQueueFilter: Filter = {
        id: uuidv7(),
        field: "annotation_queue_name",
        type: COLUMN_TYPE.string,
        operator: "=",
        value: queue.name,
      };

      if (queue.scope === AnnotationQueueScope.THREAD) {
        // Navigate to threads tab with annotation queue filter
        navigate({
          to: "/$workspaceName/projects/$projectId/traces",
          params: {
            workspaceName,
            projectId,
          },
          search: {
            type: "threads",
            threads_filters: [annotationQueueFilter],
          },
        });
      } else {
        // Navigate to traces tab with annotation queue filter
        navigate({
          to: "/$workspaceName/projects/$projectId/traces",
          params: {
            workspaceName,
            projectId,
          },
          search: {
            traces_filters: [annotationQueueFilter],
          },
        });
      }
    },
    [navigate, workspaceName, projectId],
  );

  const handleNewQueueClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const clearRowSelection = useCallback(() => {
    setRowSelection({});
  }, [setRowSelection]);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search!}
          setSearchText={setSearch}
          placeholder="Search by name"
          className="w-[320px]"
          dimension="sm"
        />
        <div className="flex items-center gap-2">
          <AnnotationQueuesActionsPanel
            queues={selectedRows}
            onClearSelection={clearRowSelection}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={columnsDef}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
          <Button variant="default" size="sm" onClick={handleNewQueueClick}>
            <Plus className="mr-2 size-4" />
            Create annotation queue
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={annotationQueues}
        onRowClick={handleRowClick}
        sortConfig={{
          enabled: true,
          sorting: sortedColumns,
          setSorting: setSortedColumns,
        }}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewQueueClick}>
                Create annotation queue
              </Button>
            )}
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page!}
          pageChange={setPage}
          size={size!}
          sizeChange={setSize}
          total={total}
        />
      </div>

      <CreateAnnotationQueueDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        defaultProjectId={projectId}
      />
    </div>
  );
};

export default AnnotationQueuesTab;
