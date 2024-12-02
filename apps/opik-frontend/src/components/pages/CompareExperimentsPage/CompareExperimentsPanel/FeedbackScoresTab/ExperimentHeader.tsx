import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import useExperimentById from "@/api/datasets/useExperimentById";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import { FeedbackScoreData } from "@/components/pages/CompareExperimentsPage/helpers";

type CustomMeta = {
  experimentId?: string;
};

const ExperimentHeader: React.FunctionComponent<
  HeaderContext<FeedbackScoreData, unknown>
> = (context) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentId } = (custom ?? {}) as CustomMeta;

  const { data: experiment } = useExperimentById(
    { experimentId: experimentId || "" },
    {
      enabled: !!experimentId,
      refetchOnMount: false,
    },
  );

  const name = experiment?.name || experimentId;
  const hasData = context.table.getRowCount() > 0;

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

export default ExperimentHeader;
