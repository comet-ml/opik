import React, { useCallback, useMemo, useState } from "react";
import { ColumnPinningState, Row } from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { DATASET_TYPE, DatasetVersion } from "@/types/datasets";
import { isLatestVersionTag } from "@/constants/datasets";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import { usePermissions } from "@/contexts/PermissionsContext";
import VersionChangeSummaryCell from "./VersionChangeSummaryCell";
import VersionNoteCell from "./VersionNoteCell";
import VersionRowActionsCell from "./VersionRowActionsCell";
import VersionRecordsSidebar from "./VersionRecordsSidebar";
import { useVersionRecordsSidebarControls } from "./useVersionRecordsSidebarControls";

interface VersionHistoryTabProps {
  datasetId: string;
  datasetName?: string;
  datasetType?: DATASET_TYPE;
}

const getRowId = (v: DatasetVersion) => v.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["version_name"],
};

const COLUMNS: ColumnData<DatasetVersion>[] = [
  {
    id: "version_name",
    label: "Version",
    type: COLUMN_TYPE.string,
  },
  {
    id: "change_summary",
    label: "Changes summary",
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.list,
    cell: VersionChangeSummaryCell as never,
  },
  {
    id: "change_description",
    label: "Version note",
    type: COLUMN_TYPE.string,
    cell: VersionNoteCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "items_total",
    label: "Item count",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => row.items_total.toLocaleString(),
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

const VersionHistoryTab: React.FC<VersionHistoryTabProps> = ({
  datasetId,
  datasetName,
  datasetType,
}) => {
  const {
    permissions: { canEditDatasets },
  } = usePermissions();
  const { openVersion: onViewVersion } = useVersionRecordsSidebarControls();

  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const {
    data: versionsData,
    isLoading,
    isPlaceholderData,
    isFetching,
  } = useDatasetVersionsList(
    {
      datasetId,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const columns = useMemo(() => {
    const baseColumns = convertColumnDataToColumn<
      DatasetVersion,
      DatasetVersion
    >(COLUMNS, {});

    baseColumns.push(
      generateActionsColumDef<DatasetVersion>({
        cell: VersionRowActionsCell,
        customMeta: { datasetId, canEdit: canEditDatasets, onViewVersion },
      }),
    );

    return baseColumns;
  }, [datasetId, canEditDatasets, onViewVersion]);

  const handleRowClick = useCallback(
    (version: DatasetVersion) => {
      if (!version.tags?.some(isLatestVersionTag)) {
        onViewVersion(version);
      }
    },
    [onViewVersion],
  );

  const getRowClassName = useCallback(
    (row: Row<DatasetVersion>) =>
      row.original.tags?.some(isLatestVersionTag) ? "cursor-default" : "",
    [],
  );

  const data = versionsData?.content || [];
  const total = versionsData?.total ?? 0;

  const isTableLoading = isLoading || (isPlaceholderData && data.length === 0);

  return (
    <div className="flex flex-col gap-4 pt-4">
      <DataTable
        columns={columns}
        data={data}
        getRowId={getRowId}
        onRowClick={handleRowClick}
        getRowClassName={getRowClassName}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title="No version history yet">
            <div className="text-sm text-muted-foreground">
              Version history will appear here when you create dataset versions
            </div>
          </DataTableNoData>
        }
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <DataTablePagination
        page={page}
        pageChange={setPage}
        size={size}
        sizeChange={setSize}
        total={total}
      />
      {datasetType && (
        <VersionRecordsSidebar
          datasetId={datasetId}
          datasetName={datasetName}
          isTestSuite={datasetType === DATASET_TYPE.TEST_SUITE}
        />
      )}
    </div>
  );
};

export default VersionHistoryTab;
