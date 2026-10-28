import React, { useRef, useState } from "react";
import { Split } from "lucide-react";
import { JsonParam, useQueryParam } from "use-query-params";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/v2/pages-shared/experiments/CompareExperimentsDialog/CompareExperimentsDialog";
import { useDatasetIdFromCompareExperimentsURL } from "@/v2/pages/CompareExperimentsPage/useDatasetIdFromCompareExperimentsURL";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";

interface CompareExperimentsButtonProps {
  size?: "default" | "sm" | "lg" | "icon";
  variant?:
    | "default"
    | "outline"
    | "ghost"
    | "link"
    | "destructive"
    | "secondary";
  className?: string;
  showIcon?: boolean;
  tooltipContent?: string;
}

const CompareExperimentsButton: React.FunctionComponent<
  CompareExperimentsButtonProps
> = ({
  size = "sm",
  variant = "default",
  className,
  showIcon = true,
  tooltipContent = "Compare experiments",
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const datasetId = useDatasetIdFromCompareExperimentsURL();
  const [experimentsIds = [], setExperimentsIds] = useQueryParam(
    "experiments",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  return (
    <>
      <CompareExperimentsDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
        datasetId={datasetId}
        experimentsIds={experimentsIds}
        onCompare={setExperimentsIds}
      />
      <div className="inline-flex items-center gap-2">
        <ExplainerIcon
          className="-mr-0.5"
          {...EXPLAINERS_MAP[
            EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
          ]}
        />
        <TooltipWrapper content={tooltipContent}>
          <Button
            size={size}
            variant={variant}
            className={className}
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            {showIcon && <Split className="mr-1.5 size-3.5" />}
            Compare
          </Button>
        </TooltipWrapper>
      </div>
    </>
  );
};

export default CompareExperimentsButton;
