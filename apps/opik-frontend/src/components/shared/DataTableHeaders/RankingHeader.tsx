import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { Trophy } from "lucide-react";

import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

const RankingHeader = <TData,>(context: HeaderContext<TData, unknown>) => {
  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Trophy className="size-3.5 shrink-0 text-yellow-500" />
    </HeaderWrapper>
  );
};

export default RankingHeader;
