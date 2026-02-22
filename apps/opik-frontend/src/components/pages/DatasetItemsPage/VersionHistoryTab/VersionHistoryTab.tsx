import React, { useMemo, useState } from "react";
import { ColumnPinningState } from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { DatasetVersion } from "@/types/datasets";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import VersionChangeSummaryCell from "./VersionChangeSummaryCell";
import VersionRowActionsCell from "./VersionRowActionsCell";

interface VersionHistoryTabProps {
  datasetId: string;
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

const VersionHistoryTab: React.FC<VersionHistoryTabProps> = ({ datasetId }) => {
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

    return [
      ...baseColumns,
      generateActionsColumDef<DatasetVersion>({
        cell: VersionRowActionsCell,
        customMeta: { datasetId },
      }),
    ];
  }, [datasetId]);

  const data = versionsData?.content || [];
  const total = versionsData?.total ?? 0;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center pt-12">
        <Loader />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 pt-4">
      <DataTable
        columns={columns}
        data={data}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title="No version history yet">
            <div className="text-sm text-muted-foreground">
              Version history will appear here when you create dataset versions
            </div>
          </DataTableNoData>
        }
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <DataTablePagination
        page={page}
        pageChange={setPage}
        size={size}
        sizeChange={setSize}
        total={total}
      />
    </div>
  );
};

export default VersionHistoryTab;
