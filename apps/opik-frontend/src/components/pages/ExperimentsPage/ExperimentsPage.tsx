import React, { useCallback, useMemo, useRef, useState } from "react";
import { Info } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import { RowSelectionState } from "@tanstack/react-table";
import get from "lodash/get";

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
import ExperimentsActionsPanel from "@/components/pages/ExperimentsShared/ExperimentsActionsPanel";
import ExperimentsFiltersButton from "@/components/pages/ExperimentsShared/ExperimentsFiltersButton";
import ExperimentRowActionsCell from "@/components/pages/ExperimentsPage/ExperimentRowActionsCell";
import ExperimentsChartsWrapper from "@/components/pages/ExperimentsPage/charts/ExperimentsChartsWrapper";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useGroupedExperimentsList, {
  checkIsMoreRowId,
  DEFAULT_GROUPS_PER_PAGE,
  GroupedExperiment,
  GROUPING_COLUMN,
} from "@/hooks/useGroupedExperimentsList";
import {
  generateExperimentNameColumDef,
  generateGroupedCellDef,
  getIsMoreRow,
  getRowId,
  GROUPING_CONFIG,
} from "@/components/pages/ExperimentsShared/table";
import { useExpandingConfig } from "@/components/pages/ExperimentsShared/useExpandingConfig";
import { useGroupLimitsConfig } from "@/components/pages/ExperimentsShared/useGroupLimitsConfig";

const SELECTED_COLUMNS_KEY = "experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "experiments-columns-width";
const COLUMNS_ORDER_KEY = "experiments-columns-order";

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
    id: "prompt",
    label: "Prompt commit",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    customMeta: {
      nameKey: "prompt_version.commit",
      idKey: "prompt_version.prompt_id",
      resource: RESOURCE_TYPE.prompt,
      getSearch: (data: GroupedExperiment) => ({
        activeVersionId: get(data, "prompt_version.id", null),
      }),
    },
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
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [datasetId, setDatasetId] = useState("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const { groupLimit, renderMoreRow } = useGroupLimitsConfig();

  const { data, isPending } = useGroupedExperimentsList({
    workspaceName,
    groupLimit,
    datasetId,
    search,
    page,
    size: DEFAULT_GROUPS_PER_PAGE,
    polling: true,
  });

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
    return experiments.filter(
      (row) => rowSelection[row.id] && !checkIsMoreRowId(row.id),
    );
  }, [rowSelection, experiments]);

  const columns = useMemo(() => {
    return [
      generateExperimentNameColumDef<GroupedExperiment>({
        size: columnsWidth["name"],
      }),
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
        enableSorting: false,
      },
    ];
  }, [selectedColumns, columnsWidth, columnsOrder]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  const expandingConfig = useExpandingConfig({
    groupIds,
  });

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

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Experiments</h1>
      </div>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
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
          <ExperimentsActionsPanel experiments={selectedRows} />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
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
      <ExperimentsChartsWrapper experiments={experiments} />
      <DataTable
        columns={columns}
        data={experiments}
        onRowClick={handleRowClick}
        renderCustomRow={renderMoreRow}
        getIsCustomRow={getIsMoreRow}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        expandingConfig={expandingConfig}
        groupingConfig={GROUPING_CONFIG}
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
          size={DEFAULT_GROUPS_PER_PAGE}
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
