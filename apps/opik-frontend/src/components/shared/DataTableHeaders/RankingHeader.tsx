import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { Trophy } from "lucide-react";

import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";
import { normalizeColumnId } from "@/lib/sorting";

const RankingHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  const { column, table } = context;
  const { rankingMetric } = (column.columnDef.meta?.custom || {}) as {
    rankingMetric?: string;
  };

  const normalizedRankingMetric = rankingMetric
    ? normalizeColumnId(rankingMetric)
    : undefined;
  const targetColumn = normalizedRankingMetric
    ? table.getAllColumns().find((col) => col.id === normalizedRankingMetric) ||
      column
    : column;

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column: targetColumn,
  });

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={className}
      onClick={onClickHandler}
    >
      <Trophy className="size-3.5 shrink-0 text-yellow-500" />
      {renderSort()}
    </HeaderWrapper>
  );
};

export default RankingHeader;
