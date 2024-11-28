import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Experiment, ExperimentsCompare } from "@/types/datasets";

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
    <div className="flex size-full items-center px-2">
      {hasData && (
        <div className="absolute left-0 top-0 h-[10000px] w-px bg-border" />
      )}
      <FlaskConical className="mr-2 size-4 shrink-0" />
      <div className="comet-body-s-accented truncate">{name}</div>
    </div>
  );
};

export default CompareExperimentsHeader;
