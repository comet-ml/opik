import React from "react";
import { FlaskConical } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import useExperimentById from "@/api/datasets/useExperimentById";
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
    <div
      className="flex size-full items-center px-2 justify-end"
      onClick={(e) => e.stopPropagation()}
    >
      {hasData && (
        <div className="absolute left-0 top-0 h-[10000px] w-px bg-border" />
      )}
      <FlaskConical className="mr-2 size-4 shrink-0" />
      <div className="comet-body-s-accented truncate">{name}</div>
    </div>
  );
};

export default ExperimentHeader;
