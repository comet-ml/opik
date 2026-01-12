import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Trophy, Medal } from "lucide-react";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const RankingCell = <TData,>(context: CellContext<TData, unknown>) => {
  const rank = context.getValue() as number | undefined;

  const renderRankContent = () => {
    if (rank === undefined) {
      return <span className="comet-body-s text-light-slate">—</span>;
    }

    if (rank === 1) {
      return (
        <>
          <Trophy className="size-4 shrink-0 text-yellow-500" />
          <span className="comet-body-s-accented ml-1">{rank}</span>
        </>
      );
    }

    if (rank === 2) {
      return (
        <>
          <Medal className="size-4 shrink-0 text-slate-400" />
          <span className="comet-body-s-accented ml-1">{rank}</span>
        </>
      );
    }

    if (rank === 3) {
      return (
        <>
          <Medal className="size-4 shrink-0 text-amber-700" />
          <span className="comet-body-s-accented ml-1">{rank}</span>
        </>
      );
    }

    return <span className="comet-body-s-accented">{rank}</span>;
  };

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex items-center justify-center">
        {renderRankContent()}
      </div>
    </CellWrapper>
  );
};

export default RankingCell;
