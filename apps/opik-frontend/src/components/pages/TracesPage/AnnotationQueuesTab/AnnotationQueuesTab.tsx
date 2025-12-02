import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnDef,
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import TagCell from "@/components/shared/DataTableCells/TagCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import AnnotateQueueCell from "@/components/pages-shared/annotation-queues/AnnotateQueueCell";
import AnnotationQueueProgressCell from "@/components/pages-shared/annotation-queues/AnnotationQueueProgressCell";
import AnnotationQueueRowActionsCell from "@/components/pages-shared/annotation-queues/AnnotationQueueRowActionsCell";
import AnnotationQueuesActionsPanel from "@/components/pages-shared/annotation-queues/AnnotationQueuesActionsPanel";
import AddEditAnnotationQueueDialog from "@/components/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import NoAnnotationQueuesPage from "@/components/pages-shared/annotation-queues/NoAnnotationQueuesPage";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";

import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { formatDate } from "@/lib/date";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/components/shared/DataTable/utils";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import useAppStore from "@/store/AppStore";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import { capitalizeFirstLetter } from "@/lib/utils";

const SHARED_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "instructions",
    label: "Instructions",
    type: COLUMN_TYPE.string,
    size: 400,
  },
  {
    id: "scope",
    label: "Scope",
    type: COLUMN_TYPE.category,
    cell: TagCell as never,
    accessorFn: (row) => capitalizeFirstLetter(row.scope),
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
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
    sortable: true,
  },
];

const DEFAULT_COLUMNS: ColumnData<AnnotationQueue>[] = [
  ...SHARED_COLUMNS,
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores (avg.)",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) => row.feedback_scores ?? [],
    cell: FeedbackScoreListCell as never,
    customMeta: {
      getHoverCardName: (row: AnnotationQueue) => row.name,
      areAggregatedScores: true,
    },
  },
  {
    id: "items_count",
    label: "Item count",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.items_count ? `${row.items_count}` : "-"),
  },
  {
    id: "reviewers",
    label: "Reviewed by",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
    accessorFn: (row) => row.reviewers?.map((r) => r.username) ?? [],
  },
  {
    id: "progress",
    label: "Progress",
    type: COLUMN_TYPE.string,
    cell: AnnotationQueueProgressCell as never,
  },
];

const FILTER_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_NAME_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "instructions",
  COLUMN_FEEDBACK_SCORES_ID,
  "progress",
  "last_updated_at",
  "scope",
  "items_count",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  "instructions",
  COLUMN_FEEDBACK_SCORES_ID,
  "last_updated_at",
  "scope",
  "items_count",
];

const SELECTED_COLUMNS_KEY = "annotation-queues-selected-columns";
const COLUMNS_WIDTH_KEY = "annotation-queues-columns-width";
const COLUMNS_ORDER_KEY = "annotation-queues-columns-order";
const COLUMNS_SORT_KEY = "annotation-queues-columns-sort";
const PAGINATION_SIZE_KEY = "annotation-queues-pagination-size";
const ROW_HEIGHT_KEY = "annotation-queues-row-height";

const FILTERS_CONFIG = {
  rowsMap: {
    scope: {
      keyComponentProps: {
        options: [
          { value: ANNOTATION_QUEUE_SCOPE.TRACE, label: "Trace" },
          { value: ANNOTATION_QUEUE_SCOPE.THREAD, label: "Thread" },
        ],
        placeholder: "Select scope",
      },
    },
  },
};

type AnnotationQueuesTabProps = {
  projectId: string;
};

const AnnotationQueuesTab: React.FC<AnnotationQueuesTabProps> = ({
  projectId,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("queues_search", StringParam, {
    updateType: "replaceIn",
  });
  const [page = 1, setPage] = useQueryParam("queues_page", NumberParam, {
    updateType: "replaceIn",
  });
  const [filters = [], setFilters] = useQueryParam(
    "queues_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 10,
  });
  const [height, setHeight] = useLocalStorageState<ROW_HEIGHT>(ROW_HEIGHT_KEY, {
    defaultValue: ROW_HEIGHT.small,
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
      defaultValue: DEFAULT_COLUMNS_ORDER,
    },
  );
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });
  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [
        {
          id: "last_updated_at",
          desc: true,
        },
      ],
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending: isLoading } = useAnnotationQueuesList(
    {
      search: search as string,
      page: page as number,
      projectId,
      size: size as number,
      filters,
      sorting: sortedColumns,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const selectedRows = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleNewQueue = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback(
    (queue: AnnotationQueue) => {
      navigate({
        to: "/$workspaceName/annotation-queues/$annotationQueueId",
        params: {
          workspaceName,
          annotationQueueId: queue.id,
        },
      });
    },
    [navigate, workspaceName],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<AnnotationQueue>(),
      mapColumnDataFields<AnnotationQueue, AnnotationQueue>({
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        sortable: isColumnSortable(COLUMN_NAME_ID, sortableBy),
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.annotationQueue,
        },
      }),
      ...convertColumnDataToColumn<AnnotationQueue, AnnotationQueue>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      {
        accessorKey: "annotate_queue",
        header: "",
        cell: AnnotateQueueCell,
        size: 140,
        enableResizing: false,
        enableHiding: false,
        enableSorting: false,
      } as ColumnDef<AnnotationQueue>,
      generateActionsColumDef({
        cell: AnnotationQueueRowActionsCell,
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

  const noDataText = useMemo(() => {
    if (search) {
      return `No annotation queues found for "${search}"`;
    }
    return "No annotation queues";
  }, [search]);

  const noData = !search && !filters.length;

  if (isLoading) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <>
        <NoAnnotationQueuesPage
          openModal={handleNewQueue}
          Wrapper={NoDataPage}
          height={188}
          className="px-6"
        />
        <AddEditAnnotationQueueDialog
          key={resetDialogKeyRef.current}
          open={openDialog}
          setOpen={setOpenDialog}
          projectId={projectId}
        />
      </>
    );
  }

  return (
    <>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerCallout
          className="mb-4"
          {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_annotation_queues]}
        />
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 py-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={FILTER_COLUMNS}
            config={FILTERS_CONFIG as never}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <AnnotationQueuesActionsPanel queues={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
          <Button size="sm" onClick={handleNewQueue}>
            Create new queue
          </Button>
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        onRowClick={handleRowClick}
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
        />
      </PageBodyStickyContainer>

      <AddEditAnnotationQueueDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        projectId={projectId}
      />
    </>
  );
};

export default AnnotationQueuesTab;
