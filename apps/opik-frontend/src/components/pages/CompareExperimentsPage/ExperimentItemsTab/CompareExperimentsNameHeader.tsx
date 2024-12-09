import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { ExperimentsCompare } from "@/types/datasets";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

const CompareExperimentsNameHeader: React.FunctionComponent<
  HeaderContext<ExperimentsCompare, unknown>
> = (context) => {
  const hasData = context.table.getRowCount() > 0;

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {hasData && (
        <div className="absolute -left-px -top-3.5 h-[10000px] w-px bg-border" />
      )}
      <FlaskConical className="size-3.5 shrink-0 text-slate-300" />
      <div className="comet-body-s-accented truncate">Experiment</div>
    </HeaderWrapper>
  );
};

export default CompareExperimentsNameHeader;
