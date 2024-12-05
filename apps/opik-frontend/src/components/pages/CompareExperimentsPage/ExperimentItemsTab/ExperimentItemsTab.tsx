import React, { useCallback, useMemo } from "react";
import findIndex from "lodash/findIndex";
import find from "lodash/find";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnPinningState } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import CompareExperimentsOutputCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsPanel";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import CompareExperimentsNameCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsNameCell";
import CompareExperimentsNameHeader from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsNameHeader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import { Separator } from "@/components/ui/separator";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { calculateHeightStyle } from "@/components/shared/DataTable/utils";
import { calculateLineHeight } from "@/components/pages/CompareExperimentsPage/helpers";

const getRowId = (d: ExperimentsCompare) => d.id;

const calculateVerticalAlignment = (count: number) =>
  count === 1 ? undefined : CELL_VERTICAL_ALIGNMENT.start;

const SELECTED_COLUMNS_KEY = "compare-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "compare-experiments-columns-width";
const COLUMNS_ORDER_KEY = "compare-experiments-columns-order";
const DYNAMIC_COLUMNS_KEY = "compare-experiments-dynamic-columns";
const COLUMNS_SCORES_ORDER_KEY = "compare-experiments-scores-columns-order";

export const FILTER_COLUMNS: ColumnData<ExperimentsCompare>[] = [
  {
    id: "feedback_scores",
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: "output",
    label: "Output",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id"];

export type ExperimentItemsTabProps = {
  experimentsIds: string[];
  experiments?: Experiment[];
};

const ExperimentItemsTab: React.FunctionComponent<ExperimentItemsTabProps> = ({
  experimentsIds = [],
  experiments,
}) => {
  const datasetId = useDatasetIdFromCompareExperimentsURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [activeRowId = "", setActiveRowId] = useQueryParam("row", StringParam, {
    updateType: "replaceIn",
  });

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 100, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [height = ROW_HEIGHT.small, setHeight] = useQueryParam(
    "height",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

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

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_SCORES_ORDER_KEY, {
    defaultValue: [],
  });

  const { data, isPending } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds,
      filters,
      truncate: true,
      page: page as number,
      size: size as number,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {
        experimentsIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const experimentsCount = experimentsIds.length;
  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There is no data for the selected experiments";

  const dynamicDatasetColumns = useMemo(() => {
    return (data?.columns ?? []).map<DynamicColumn>((c) => ({
      id: c.name,
      label: c.name,
      columnType: mapDynamicColumnTypesToColumnType(c.types),
    }));
  }, [data]);

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? []).map<DynamicColumn>((c) => ({
      id: `feedback_scores.${c.name}`,
      label: c.name,
      columnType: COLUMN_TYPE.number,
    }));
  }, [feedbackScoresData?.scores]);

  const dynamicColumnsIds = useMemo(
    () => [
      ...dynamicDatasetColumns.map((c) => c.id),
      ...dynamicScoresColumns.map((c) => c.id),
    ],
    [dynamicDatasetColumns, dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const columnsData = useMemo(() => {
    const retVal: ColumnData<ExperimentsCompare>[] = dynamicDatasetColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          accessorFn: (row) => get(row, ["data", label], ""),
          cell: AutodetectCell as never,
          verticalAlignment: calculateVerticalAlignment(experimentsCount),
          overrideRowHeight:
            experimentsCount === 1 ? undefined : ROW_HEIGHT.large,
        }) as ColumnData<ExperimentsCompare>,
    );

    retVal.push({
      id: "created_at",
      label: "Created",
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
      verticalAlignment: calculateVerticalAlignment(experimentsCount),
    });

    return retVal;
  }, [dynamicDatasetColumns, experimentsCount]);

  const scoresColumnsData = useMemo(() => {
    return [
      ...dynamicScoresColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: CompareExperimentsFeedbackScoreCell as never,
            customMeta: {
              experimentsIds,
              feedbackKey: label,
            },
          }) as ColumnData<ExperimentsCompare>,
      ),
    ];
  }, [dynamicScoresColumns, experimentsIds]);

  const handleRowClick = useCallback(
    (row: ExperimentsCompare) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const experimentColumns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<ExperimentsCompare, ExperimentsCompare>(
        [
          ...(experimentsCount > 1
            ? [
                {
                  id: "experiment_name",
                  label: "Experiment",
                  header: CompareExperimentsNameHeader as never,
                  cell: CompareExperimentsNameCell as never,
                  customMeta: {
                    experiments,
                    experimentsIds,
                  },
                },
              ]
            : []),
          {
            id: "output",
            label: "Output",
            type: COLUMN_TYPE.dictionary,
            cell: CompareExperimentsOutputCell as never,
            customMeta: {
              openTrace: setTraceId,
              experimentsIds,
            },
            size: 400,
          },
        ],
        {
          columnsWidth,
        },
      ),

      ...convertColumnDataToColumn<ExperimentsCompare, ExperimentsCompare>(
        scoresColumnsData,
        {
          columnsWidth,
          selectedColumns,
          columnsOrder: scoresColumnsOrder,
        },
      ),
    ];
  }, [
    scoresColumnsData,
    scoresColumnsOrder,
    columnsWidth,
    selectedColumns,
    experimentsCount,
    experiments,
    experimentsIds,
    setTraceId,
  ]);

  const columns = useMemo(() => {
    return [
      mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
        id: COLUMN_ID_ID,
        label: "Item ID",
        type: COLUMN_TYPE.string,
        size: columnsWidth[COLUMN_ID_ID],
        cell: LinkCell as never,
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
      }),
      ...convertColumnDataToColumn<ExperimentsCompare, ExperimentsCompare>(
        columnsData,
        {
          columnsWidth,
          selectedColumns,
          columnsOrder,
        },
      ),
      ...experimentColumns,
    ];
  }, [
    columnsWidth,
    handleRowClick,
    columnsData,
    selectedColumns,
    columnsOrder,
    experimentColumns,
    experimentsCount,
  ]);

  const filterColumns = useMemo(() => {
    const retVal: ColumnData<ExperimentsCompare>[] = [...FILTER_COLUMNS];

    sortBy(dynamicDatasetColumns, "label").forEach(
      ({ id, label, columnType }) => {
        retVal.push({
          id,
          label,
          type: columnType,
        });
      },
    );

    return retVal;
  }, [dynamicDatasetColumns]);

  const rowIndex = findIndex(rows, (row) => activeRowId === row.id);

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => {
      setActiveRowId(rows[rowIndex + shift]?.id ?? "");
    },
    [rowIndex, rows, setActiveRowId],
  );

  const handleClose = useCallback(() => setActiveRowId(""), [setActiveRowId]);

  const activeRow = useMemo(
    () => find(rows, (row) => activeRowId === row.id),
    [activeRowId, rows],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  const getRowHeightStyle = useCallback(
    (height: ROW_HEIGHT) => {
      let retVal = calculateHeightStyle(height);

      if (experimentsCount > 1) {
        retVal = calculateLineHeight(height, experimentsCount);
      }

      return retVal;
    },
    [experimentsCount],
  );

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <FiltersButton
            columns={filterColumns}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={columnsData}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            extraSection={{
              title: "Feedback Scores",
              columns: scoresColumnsData,
              order: scoresColumnsOrder,
              onOrderChange: setScoresColumnsOrder,
            }}
          ></ColumnsButton>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightStyle={getRowHeightStyle}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={total}
        ></DataTablePagination>
      </div>
      <CompareExperimentsPanel
        experimentsCompareId={activeRowId}
        experimentsCompare={activeRow}
        experimentsIds={experimentsIds}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        openTrace={setTraceId as OnChangeFn<string>}
        onClose={handleClose}
        onRowChange={handleRowChange}
        isTraceDetailsOpened={Boolean(traceId)}
      />
      <TraceDetailsPanel
        traceId={traceId as string}
        spanId={spanId as string}
        setSpanId={setSpanId}
        onClose={() => {
          setTraceId("");
        }}
      />
    </div>
  );
};

export default ExperimentItemsTab;
