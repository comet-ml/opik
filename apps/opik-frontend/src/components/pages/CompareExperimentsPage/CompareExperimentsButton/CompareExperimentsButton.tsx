import React, { useRef, useState } from "react";
import { Split } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/components/pages/CompareExperimentsPage/CompareExperimentsDialog";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

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

  return (
    <>
      <CompareExperimentsDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
      />
      <div className="inline-flex items-center gap-2">
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
        <ExplainerIcon
          className="-ml-0.5"
          {...EXPLAINERS_MAP[
            EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
          ]}
        />
      </div>
    </>
  );
};

export default CompareExperimentsButton;
