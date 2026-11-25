import React, { useMemo } from "react";
import { ColumnDef, ColumnPinningState } from "@tanstack/react-table";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import DataTable from "@/shared/DataTable/DataTable";
import TextCell from "@/shared/DataTableCells/TextCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import VersionChangeSummaryCell from "@/v2/pages-shared/datasets/VersionHistoryTab/VersionChangeSummaryCell";
import { convertColumnDataToColumn } from "@/lib/table";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { HistoryVersion } from "@/lib/model-prices";

type HistoryRow = {
  id: string;
  version_name: string;
  items_added: number;
  items_modified: number;
  items_deleted: number;
  changes: string;
  created_at: string;
  created_by: string;
};

const COLUMNS: ColumnData<HistoryRow>[] = [
  {
    id: "version_name",
    label: "Version",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    size: 90,
  },
  {
    id: "change_summary",
    label: "Changes summary",
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.list,
    cell: VersionChangeSummaryCell as never,
    size: 170,
  },
  {
    id: "changes",
    label: "Changes",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    size: 380,
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
    cell: TextCell as never,
  },
];

const COLUMN_DEFS: ColumnDef<HistoryRow>[] = convertColumnDataToColumn<
  HistoryRow,
  HistoryRow
>(COLUMNS, {});

const COLUMN_PINNING: ColumnPinningState = {
  left: ["version_name"],
  right: [],
};

type HistorySheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  history: HistoryVersion[];
};

const HistorySheet: React.FC<HistorySheetProps> = ({
  open,
  setOpen,
  history,
}) => {
  const rows = useMemo<HistoryRow[]>(
    () =>
      history.map((version) => ({
        id: String(version.version),
        version_name: `v${version.version}`,
        items_added: version.added,
        items_modified: version.changed,
        items_deleted: version.removed,
        changes: version.lines.join("; "),
        created_at: version.createdAt,
        created_by: version.createdBy,
      })),
    [history],
  );

  return (
    <Sheet open={open} onOpenChange={setOpen}>
      <SheetContent
        className="flex w-full max-w-none flex-col sm:max-w-[960px]"
        header={<SheetTopBar variant="info" title="Price history" />}
        aria-describedby={undefined}
        onOpenAutoFocus={(event) => event.preventDefault()}
      >
        <div
          className="flex-1 overflow-auto p-6"
          data-testid="model-price-history"
        >
          <DataTable
            columns={COLUMN_DEFS}
            data={rows}
            getRowId={(row) => row.id}
            columnPinning={COLUMN_PINNING}
          />
          <p className="comet-body-xs mt-3 text-light-slate">
            Every save keeps each row before and after, so any change can be
            traced.
          </p>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default HistorySheet;
