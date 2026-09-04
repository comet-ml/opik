import React, { useMemo, useState } from "react";
import get from "lodash/get";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import AutodetectCell from "@/shared/DataTableCells/AutodetectCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import useDatasetItemsList from "@/api/datasets/useDatasetItemsList";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import { convertColumnDataToColumn } from "@/lib/table";
import { DatasetItem, DatasetVersion } from "@/types/datasets";
import {
  COLUMN_DATA_ID,
  COLUMN_ID_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";

const getRowId = (d: DatasetItem) => d.id;

type ViewVersionDataDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  datasetId: string;
  version: DatasetVersion;
};

const ViewVersionDataDialog: React.FC<ViewVersionDataDialogProps> = ({
  open,
  setOpen,
  datasetId,
  version,
}) => {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const { data, isLoading, isPlaceholderData, isFetching } =
    useDatasetItemsList(
      {
        datasetId,
        page,
        size,
        versionId: version.version_hash,
      },
      {
        enabled: open,
        placeholderData: keepPreviousData,
      },
    );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);

  const columns = useMemo(() => {
    const dynamicColumns: ColumnData<DatasetItem>[] = (data?.columns ?? []).map(
      (c) =>
        ({
          id: `${COLUMN_DATA_ID}.${c.name}`,
          label: c.name,
          type: mapDynamicColumnTypesToColumnType(c.types),
          accessorFn: (row) => get(row, ["data", c.name], ""),
          cell: AutodetectCell as never,
        }) as ColumnData<DatasetItem>,
    );

    const columnsData: ColumnData<DatasetItem>[] = [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
      ...dynamicColumns,
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      },
    ];

    return convertColumnDataToColumn<DatasetItem, DatasetItem>(columnsData, {});
  }, [data?.columns]);

  const isTableLoading = isLoading || (isPlaceholderData && rows.length === 0);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="flex max-h-[85vh] max-w-3xl flex-col">
        <DialogHeader>
          <DialogTitle>Version {version.version_name} data</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="flex-1">
          <p className="text-sm text-muted-foreground">
            Records of this version in read-only mode.
          </p>

          <DataTable
            columns={columns}
            data={rows}
            getRowId={getRowId}
            noData={<DataTableNoData title="No records in this version" />}
            showSkeleton={isTableLoading}
            showLoadingOverlay={
              !isTableLoading && isPlaceholderData && isFetching
            }
          />
          <DataTablePagination
            page={page}
            pageChange={setPage}
            size={size}
            sizeChange={setSize}
            total={data?.total ?? 0}
          />
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default ViewVersionDataDialog;
