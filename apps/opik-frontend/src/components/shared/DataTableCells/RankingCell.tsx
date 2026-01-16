import { CellContext } from "@tanstack/react-table";
import { Medal } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { cn } from "@/lib/utils";

const MEDAL_COLORS: Record<number, string> = {
  1: "text-yellow-500",
  2: "text-slate-400",
  3: "text-amber-700",
};

interface CustomMeta {
  getRank: (rowId: string) => number | undefined;
}

interface RowWithId {
  id: string;
}

const RankingCell = <TData extends RowWithId>(
  context: CellContext<TData, unknown>,
) => {
  const customMeta = context.column.columnDef.meta?.custom as
    | CustomMeta
    | undefined;
  const rowId = context.row.original.id;
  const rank = customMeta?.getRank(rowId);
  const medalColor = rank ? MEDAL_COLORS[rank] : null;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex items-center justify-center">
        {rank === undefined ? (
          <span className="comet-body-s text-slate-400">—</span>
        ) : (
          <>
            {medalColor && (
              <Medal className={cn("size-3.5 shrink-0", medalColor)} />
            )}
            <span className={cn("comet-code", medalColor && "ml-1")}>
              {rank}
            </span>
          </>
        )}
      </div>
    </CellWrapper>
  );
};

export default RankingCell;
