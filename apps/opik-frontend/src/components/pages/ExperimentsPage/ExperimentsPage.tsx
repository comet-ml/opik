import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Info } from "lucide-react";
import last from "lodash/last";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import {
  ExpandedState,
  GroupingState,
  RowSelectionState,
  Row,
} from "@tanstack/react-table";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import FeedbackScoresCell from "@/components/shared/DataTableCells/FeedbackScoresCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddExperimentDialog from "@/components/pages/ExperimentsPage/AddExperimentDialog";
import ExperimentsActionsButton from "@/components/pages/ExperimentsPage/ExperimentsActionsButton";
import ExperimentsFiltersButton from "@/components/pages/ExperimentsPage/ExperimentsFiltersButton";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { ExperimentRowActionsCell } from "@/components/pages/ExperimentsPage/ExperimentRowActionsCell";
import { Button } from "@/components/ui/button";
import useGroupedExperimentsList, {
  DEFAULT_EXPERIMENTS_PER_GROUP,
  GroupedExperiment,
  GROUPING_COLUMN,
} from "@/hooks/useGroupedExperimentsList";
import {
  generateExperimentNameColumDef,
  generateGroupedCellDef,
} from "@/components/pages/ExperimentsPage/table";

const SELECTED_COLUMNS_KEY = "experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "experiments-columns-width";
const COLUMNS_ORDER_KEY = "experiments-columns-order";

const getRowId = (e: GroupedExperiment) => e.id;

export const DEFAULT_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: "id",
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "trace_count",
    label: "Trace count",
    type: COLUMN_TYPE.number,
  },
  {
    id: "feedback_scores",
    label: "Feedback scores (average)",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoresCell as never,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "created_at",
  "feedback_scores",
];

const ExperimentsPage: React.FunctionComponent = () => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const openGroupsRef = useRef<Record<string, boolean>>({});
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(5);
  const [datasetId, setDatasetId] = useState("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [expanded, setExpanded] = useState<ExpandedState>({});
  const [groupLimit, setGroupLimit] = useState<Record<string, number>>({});

  const { data, isPending } = useGroupedExperimentsList(
    {
      workspaceName,
      groupLimit,
      datasetId,
      search,
      page,
      size,
    },
    { refetchInterval: 30000 },
  );

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);
  const groupIds = useMemo(() => data?.groupIds ?? [], [data?.groupIds]);
  const total = data?.total ?? 0;
  const noData = !search && !datasetId;
  const noDataText = noData
    ? "There are no experiments yet"
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

  const selectedRows: Array<GroupedExperiment> = useMemo(() => {
    return experiments.filter((row) => rowSelection[row.id]);
  }, [rowSelection, experiments]);

  const columns = useMemo(() => {
    return [
      generateExperimentNameColumDef<GroupedExperiment>(),
      generateGroupedCellDef<GroupedExperiment, unknown>({
        id: GROUPING_COLUMN,
        label: "Dataset",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "dataset_name",
          idKey: "dataset_id",
          resource: RESOURCE_TYPE.dataset,
        },
      }),
      ...convertColumnDataToColumn<GroupedExperiment, GroupedExperiment>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          columnsWidth,
          selectedColumns,
        },
      ),
      {
        id: "actions",
        enableHiding: false,
        cell: ExperimentRowActionsCell,
        size: 48,
        enableResizing: false,
      },
    ];
  }, [selectedColumns, columnsWidth, columnsOrder]);

  useEffect(() => {
    const updateForExpandedState: Record<string, boolean> = {};
    groupIds.forEach((groupId) => {
      const id = `${GROUPING_COLUMN}:${groupId}`;
      if (!openGroupsRef.current[id]) {
        openGroupsRef.current[id] = true;
        updateForExpandedState[id] = true;
      }
    });

    if (Object.keys(updateForExpandedState).length) {
      setExpanded((state) => {
        if (state === true) return state;
        return {
          ...state,
          ...updateForExpandedState,
        };
      });
    }
  }, [groupIds]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  const selectionConfig = useMemo(
    () => ({
      rowSelection,
      setRowSelection,
    }),
    [rowSelection, setRowSelection],
  );

  const groupingConfig = useMemo(
    () => ({
      groupedColumnMode: false as const,
      grouping: [GROUPING_COLUMN] as GroupingState,
    }),
    [],
  );

  const expandingConfig = useMemo(
    () => ({
      autoResetExpanded: false,
      expanded,
      setExpanded,
    }),
    [expanded, setExpanded],
  );

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback(
    (experiment: GroupedExperiment) => {
      navigate({
        to: "/$workspaceName/experiments/$datasetId/compare",
        params: {
          datasetId: experiment.dataset_id,
          workspaceName,
        },
        search: {
          experiments: [experiment.id],
        },
      });
    },
    [navigate, workspaceName],
  );

  const renderMoreRow = useCallback((row: Row<GroupedExperiment>) => {
    return (
      <tr key={row.id} className="border-b">
        <td colSpan={row.getAllCells().length} className="px-2 py-1">
          <Button
            variant="link"
            className="w-full"
            onClick={() => {
              setGroupLimit((state) => {
                return {
                  ...state,
                  [row.original.dataset_id]:
                    (state[row.original.dataset_id] ||
                      DEFAULT_EXPERIMENTS_PER_GROUP) +
                    DEFAULT_EXPERIMENTS_PER_GROUP,
                };
              });
            }}
          >
            Load {DEFAULT_EXPERIMENTS_PER_GROUP} more experiments
          </Button>
        </td>
      </tr>
    );
  }, []);

  const getIsMoreRow = useCallback((row: Row<GroupedExperiment>) => {
    return last((row?.original?.id || "").split("_")) === "more";
  }, []);

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Experiments</h1>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
          ></SearchInput>
          <ExperimentsFiltersButton
            datasetId={datasetId}
            onChangeDatasetId={setDatasetId}
          />
        </div>
        <div className="flex items-center gap-2">
          {selectedRows.length > 0 && (
            <ExperimentsActionsButton experiments={selectedRows} />
          )}
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
          <Button variant="outline" onClick={handleNewExperimentClick}>
            <Info className="mr-2 size-4" />
            Create new experiment
          </Button>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={experiments}
        onRowClick={handleRowClick}
        renderCustomRow={renderMoreRow}
        getIsCustomRow={getIsMoreRow}
        resizeConfig={resizeConfig}
        selectionConfig={selectionConfig}
        expandingConfig={expandingConfig}
        groupingConfig={groupingConfig}
        getRowId={getRowId}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewExperimentClick}>
                Create new experiment
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
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default ExperimentsPage;
