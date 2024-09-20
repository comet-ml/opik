import React from "react";
import { FlaskConical, X } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Button } from "@/components/ui/button";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { JsonParam, useQueryParam } from "use-query-params";

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
  const [experimentIds, setExperimentsIds] = useQueryParam(
    "experiments",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const name = experiment?.name || experimentId;

  return (
    <div
      className="flex size-full items-center px-2"
      onClick={(e) => e.stopPropagation()}
    >
      {hasData && (
        <div className="absolute left-0 top-0 h-[10000px] w-px bg-border"></div>
      )}
      <FlaskConical className="mr-2 size-4 shrink-0" />
      <div className="comet-body-s-accented truncate">{name}</div>
      {experimentIds.length > 1 && (
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={() => {
            setExperimentsIds((state: string[]) =>
              state.filter((id) => id !== experimentId),
            );
          }}
        >
          <X className="size-4" />
        </Button>
      )}
    </div>
  );
};

export default CompareExperimentsHeader;
