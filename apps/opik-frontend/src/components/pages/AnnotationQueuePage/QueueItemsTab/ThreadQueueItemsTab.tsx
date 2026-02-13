import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  CellContext,
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import isNumber from "lodash/isNumber";

import {
  COLUMN_COMMENTS_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  COLUMN_USAGE_ID,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { Thread } from "@/types/traces";
import { AnnotationQueue } from "@/types/annotation-queues";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { Separator } from "@/components/ui/separator";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import QueueItemActionsPanel from "@/components/pages/AnnotationQueuePage/QueueItemsTab/QueueItemActionsPanel";
import QueueItemRowActionsCell from "@/components/pages/AnnotationQueuePage/QueueItemsTab/QueueItemRowActionsCell";
import NoQueueItemsPage from "@/components/pages/AnnotationQueuePage/QueueItemsTab/NoQueueItemsPage";
import useThreadsList from "@/api/traces/useThreadsList";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { generateTracesURL } from "@/lib/annotation-queues";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import useAppStore from "@/store/AppStore";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";
import SelectBox, {
  SelectBoxProps,
} from "@/components/shared/SelectBox/SelectBox";
import { useTruncationEnabled } from "@/components/server-sync-provider";

const SHARED_COLUMNS: ColumnData<Thread>[] = [
  {
    id: "first_message",
    label: "First message",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "input",
    },
  },
  {
    id: "last_message",
    label: "Last message",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "output",
    },
  },
  {
    id: "number_of_messages",
    label: "Message count",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      isNumber(row.number_of_messages) ? `${row.number_of_messages}` : "-",
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    sortable: true,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
    cell: DurationCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
];

const DEFAULT_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
    sortable: true,
  },
  ...SHARED_COLUMNS,
  {
    id: `${COLUMN_USAGE_ID}.total_tokens`,
    label: "Total tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.total_tokens)
        ? `${row.usage.total_tokens}`
        : "-",
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.hows_the_thread_cost_estimated],
    size: 160,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: "Comments",
    type: COLUMN_TYPE.string,
    cell: CommentsCell as never,
  },
];

const FILTER_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "first_message",
  "last_message",
  "comments",
];

const SELECTED_COLUMNS_KEY = "queue-thread-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "queue-thread-columns-width";
const COLUMNS_ORDER_KEY = "queue-thread-columns-order";
const COLUMNS_SORT_KEY = "queue-thread-columns-sort";
const COLUMNS_SCORES_ORDER_KEY = "queue-thread-columns-scores-order";
const PAGINATION_SIZE_KEY = "queue-thread-pagination-size";
const ROW_HEIGHT_KEY = "queue-thread-row-height";

interface ThreadQueueItemsTabProps {
  annotationQueue: AnnotationQueue;
}

const ThreadQueueItemsTab: React.FunctionComponent<
  ThreadQueueItemsTabProps
> = ({ annotationQueue }) => {
  const truncationEnabled = useTruncationEnabled();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [search = "", setSearch] = useQueryParam("thread_search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("thread_page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "thread_height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [filters = [], setFilters] = useQueryParam(
    "thread_filters",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: "thread_sorting",
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_SCORES_ORDER_KEY, {
    defaultValue: [],
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const extendedFilters = useMemo(
    () => [...filters, ...generateAnnotationQueueIdFilter(annotationQueue.id)],
    [annotationQueue.id, filters],
  );

  const { data, isPending, isPlaceholderData, isFetching } = useThreadsList(
    {
      projectId: annotationQueue.project_id,
      sorting: sortedColumns,
      filters: extendedFilters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: truncationEnabled,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? "There are no items in this queue yet"
    : "No search results";

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: (
            props: {
              onValueChange: SelectBoxProps<string>["onChange"];
            } & SelectBoxProps<string>,
          ) => <SelectBox {...props} onChange={props.onValueChange} />,
          keyComponentProps: {
            options: (annotationQueue.feedback_definition_names ?? [])
              .sort()
              .map((key) => ({ value: key, label: key })),
            placeholder: "Select score",
          },
        },
      },
    }),
    [annotationQueue.feedback_definition_names],
  );

  const dynamicScoresColumns = useMemo(() => {
    return (annotationQueue.feedback_definition_names ?? [])
      .sort()
      .map<DynamicColumn>((name) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${name}`,
        label: name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [annotationQueue.feedback_definition_names]);

  const scoresColumnsData = useMemo(() => {
    return [
      ...dynamicScoresColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            accessorFn: (row) =>
              row.feedback_scores?.find((f) => f.name === label),
            statisticKey: `${COLUMN_FEEDBACK_SCORES_ID}.${label}`,
            statisticDataFormater: formatScoreDisplay,
          }) as ColumnData<Thread>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const rows: Thread[] = useMemo(() => data?.content ?? [], [data]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_ID_ID],
      ),
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

  const selectedRows: Thread[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  // TODO: Temporary workaround to open in new tab until sidebars are integrated in the page
  const handleRowClick = useCallback(
    (row: Thread) => {
      if (!row) return;

      const url = generateTracesURL(
        workspaceName,
        annotationQueue.project_id,
        "threads",
        row.id,
      );
      window.open(url, "_blank");
    },
    [workspaceName, annotationQueue.project_id],
  );

  const columns = useMemo(() => {
    const convertedColumns = convertColumnDataToColumn<Thread, Thread>(
      DEFAULT_COLUMNS,
      {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      },
    );

    return [
      generateSelectColumDef<Thread>(),
      ...convertedColumns,
      ...convertColumnDataToColumn<Thread, Thread>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      generateActionsColumDef({
        cell: QueueItemRowActionsCell as React.FC<CellContext<Thread, unknown>>,
        customMeta: {
          annotationQueueId: annotationQueue.id,
        },
      }),
    ];
  }, [
    sortableBy,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    annotationQueue.id,
  ]);

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

  const columnSections = useMemo(() => {
    return [
      {
        title: "Feedback scores",
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];
  }, [scoresColumnsData, scoresColumnsOrder, setScoresColumnsOrder]);

  if (isPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <NoQueueItemsPage
        queueScope={annotationQueue.scope}
        annotationQueue={annotationQueue}
        Wrapper={NoDataPage}
        height={278}
        className="px-6"
      />
    );
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
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={FILTER_COLUMNS}
            filters={filters}
            onChange={setFilters}
            config={filtersConfig as never}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <QueueItemActionsPanel
            items={selectedRows}
            annotationQueueId={annotationQueue.id}
          />
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
            sections={columnSections}
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
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
        TableWrapper={PageBodyStickyTableWrapper}
        stickyHeader
        showLoadingOverlay={isPlaceholderData && isFetching}
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
          supportsTruncation
          truncationEnabled={truncationEnabled}
        />
      </PageBodyStickyContainer>
    </>
  );
};

export default ThreadQueueItemsTab;
