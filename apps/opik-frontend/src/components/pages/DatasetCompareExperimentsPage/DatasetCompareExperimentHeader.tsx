import React from "react";
import { FlaskConical, X } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Button } from "@/components/ui/button";
import { ExperimentsCompare } from "@/types/datasets";
import { JsonParam, useQueryParam } from "use-query-params";
import useExperimentById from "@/api/datasets/useExperimentById";

export const DatasetCompareExperimentsHeader: React.FunctionComponent<
  HeaderContext<ExperimentsCompare, unknown>
> = ({ header }) => {
  const experimentId = header?.id;
  const [experimentIds, setExperimentsIds] = useQueryParam(
    "experiments",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data } = useExperimentById({
    experimentId,
  });

  const name = data?.name || experimentId;

  return (
    <div
      className="flex size-full items-center"
      onClick={(e) => e.stopPropagation()}
    >
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
