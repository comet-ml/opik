import { CellContext } from "@tanstack/react-table";
import { Medal } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const MEDAL_COLORS: Record<number, string> = {
  1: "text-yellow-500",
  2: "text-slate-400",
  3: "text-amber-700",
};

const RankingCell = <TData,>(context: CellContext<TData, unknown>) => {
  const rank = context.getValue() as number | undefined;
  const medalColor = rank ? MEDAL_COLORS[rank] : null;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex items-center justify-center">
        {rank === undefined ? (
          <span className="comet-body-s text-light-slate">—</span>
        ) : (
          <>
            {medalColor && (
              <Medal className={`size-4 shrink-0 ${medalColor}`} />
            )}
            <span
              className={`comet-body-s-accented ${medalColor ? "ml-1" : ""}`}
            >
              {rank}
            </span>
          </>
        )}
      </div>
    </CellWrapper>
  );
};

export default RankingCell;
