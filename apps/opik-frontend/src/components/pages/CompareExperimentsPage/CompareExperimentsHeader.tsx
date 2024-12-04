import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Experiment, ExperimentsCompare } from "@/types/datasets";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

type CustomMeta = {
  experiment?: Experiment;
};

const CompareExperimentsHeader: React.FunctionComponent<
  HeaderContext<ExperimentsCompare, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { experiment } = (custom ?? {}) as CustomMeta;
  const experimentId = context.header?.id;
  const hasData = context.table.getRowCount() > 0;

  const name = experiment?.name || experimentId;

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {hasData && (
        <div className="absolute left-0 top-0 h-[10000px] w-px bg-border" />
      )}
      <FlaskConical className="size-3.5 shrink-0 text-slate-300" />
      <div className="comet-body-s-accented truncate">{name}</div>
    </HeaderWrapper>
  );
};

export default CompareExperimentsHeader;
