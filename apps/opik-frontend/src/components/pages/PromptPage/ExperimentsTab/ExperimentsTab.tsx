import React, { useCallback, useMemo, useState } from "react";
import {
  ColumnPinningState,
  Row,
  RowSelectionState,
} from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";

import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ExperimentsFiltersButton from "@/components/pages/ExperimentsShared/ExperimentsFiltersButton";
import ExperimentsActionsPanel from "@/components/pages/ExperimentsShared/ExperimentsActionsPanel";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import FeedbackScoresCell from "@/components/shared/DataTableCells/FeedbackScoresCell";
import useAppStore from "@/store/AppStore";
import useGroupedExperimentsList, {
  checkIsMoreRowId,
  DEFAULT_GROUPS_PER_PAGE,
  GroupedExperiment,
  GROUPING_COLUMN,
} from "@/hooks/useGroupedExperimentsList";
import {
  generateExperimentNameColumDef,
  generateGroupedCellDef,
  getIsCustomRow,
  getRowId,
  GROUPING_CONFIG,
  renderCustomRow,
} from "@/components/pages/ExperimentsShared/table";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { formatDate } from "@/lib/date";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { useExpandingConfig } from "@/components/pages/ExperimentsShared/useExpandingConfig";
import { convertColumnDataToColumn } from "@/lib/table";

const COLUMNS_WIDTH_KEY = "prompt-experiments-columns-width";

export const DEFAULT_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: "id",
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
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "feedback_scores",
    label: "Feedback scores (average)",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoresCell as never,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID, GROUPING_COLUMN],
  right: [],
};

interface ExperimentsTabProps {
  promptId: string;
}

const ExperimentsTab: React.FC<ExperimentsTabProps> = ({ promptId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [datasetId, setDatasetId] = useState("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [groupLimit, setGroupLimit] = useState<Record<string, number>>({});

  const { data, isPending } = useGroupedExperimentsList({
    workspaceName,
    groupLimit,
    datasetId,
    promptId,
    search,
    page,
    size: DEFAULT_GROUPS_PER_PAGE,
  });

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);
  const groupIds = useMemo(() => data?.groupIds ?? [], [data?.groupIds]);
  const total = data?.total ?? 0;
  const noData = !search && !datasetId;
  const noDataText = noData
    ? "There are no experiments used this prompt"
    : "No search results";

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
        size: columnsWidth[COLUMN_NAME_ID],
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
          columnsWidth,
        },
      ),
    ];
  }, [columnsWidth]);

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

  const renderCustomRowCallback = useCallback(
    (row: Row<GroupedExperiment>) => {
      return renderCustomRow(row, setGroupLimit);
    },
    [setGroupLimit],
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
        </div>
      </div>
      <DataTable
        columns={columns}
        data={experiments}
        renderCustomRow={renderCustomRowCallback}
        getIsCustomRow={getIsCustomRow}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        expandingConfig={expandingConfig}
        groupingConfig={GROUPING_CONFIG}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText}></DataTableNoData>}
      />
      <div className="py-4">
        <DataTablePagination
          page={page}
          pageChange={setPage}
          size={DEFAULT_GROUPS_PER_PAGE}
          total={total}
        ></DataTablePagination>
      </div>
    </div>
  );
};

export default ExperimentsTab;
