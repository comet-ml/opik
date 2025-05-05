import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { ExperimentsCompare } from "@/types/datasets";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

const CompareExperimentsNameHeader: React.FC<
  HeaderContext<ExperimentsCompare, unknown>
> = (context) => {
  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <FlaskConical className="size-3.5 shrink-0 text-slate-300" />
      <div className="comet-body-s-accented truncate">Name</div>
    </HeaderWrapper>
  );
};

export default CompareExperimentsNameHeader;
