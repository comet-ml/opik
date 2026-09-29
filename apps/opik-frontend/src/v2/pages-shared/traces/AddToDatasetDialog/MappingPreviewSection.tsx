import React, { useMemo, useState } from "react";
import { ColumnSizingState } from "@tanstack/react-table";

import DataTable from "@/shared/DataTable/DataTable";
import { convertColumnDataToColumn } from "@/lib/table";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import MappingPreviewCell from "./MappingPreviewCell";
import { PreviewRow } from "./useMappingPreview";

const COLUMN_WIDTH = 200;
const COLUMN_MIN_WIDTH = 100;
const COLUMN_MAX_WIDTH = 480;

type MappingPreviewSectionProps = {
  columns: string[];
  rows: PreviewRow[];
  isPending: boolean;
  hasOnlySpans: boolean;
};

const MappingPreviewSection: React.FunctionComponent<
  MappingPreviewSectionProps
> = ({ columns, rows, isPending, hasOnlySpans }) => {
  const [columnSizing, setColumnSizing] = useState<ColumnSizingState>({});

  const tableColumns = useMemo(
    () =>
      convertColumnDataToColumn<PreviewRow, PreviewRow>(
        columns.map<ColumnData<PreviewRow>>((key) => ({
          id: key,
          label: key,
          type: COLUMN_TYPE.string,
          accessorFn: (row) => row.cells[key],
          cell: MappingPreviewCell as never,
          size: COLUMN_WIDTH,
          minSize: COLUMN_MIN_WIDTH,
        })),
        {},
      ).map((column) => ({ ...column, maxSize: COLUMN_MAX_WIDTH })),
    [columns],
  );

  if (!isPending && (columns.length === 0 || rows.length === 0)) return null;

  return (
    <div className="mb-4 flex flex-col gap-2">
      <div className="flex flex-col">
        <span className="comet-body-xs-accented">Preview</span>
        <span className="comet-body-xs text-muted-slate">
          How items will look once it&apos;s in the dataset. Values are read
          from sample of selected {hasOnlySpans ? "spans" : "traces"}
        </span>
      </div>

      <DataTable
        columns={tableColumns}
        data={rows}
        getRowId={(row) => row.id}
        showSkeleton={isPending}
        resizeConfig={{
          enabled: true,
          columnSizing,
          onColumnResize: setColumnSizing,
        }}
      />
    </div>
  );
};

export default MappingPreviewSection;
