import React from "react";
import { Check, ChevronRight, Info } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Dataset } from "@/types/datasets";

interface DatasetOptionProps {
  dataset: Dataset;
  isSelected: boolean;
  isOpen: boolean;
  showChevron: boolean;
  isDisabled?: boolean;
  disabledTooltip?: string;
  onMainAreaClick: () => void;
  onChevronMouseEnter: () => void;
  onChevronMouseLeave: () => void;
}

const DatasetOption = React.forwardRef<HTMLDivElement, DatasetOptionProps>(
  (
    {
      dataset,
      isSelected,
      isOpen,
      showChevron,
      isDisabled = false,
      disabledTooltip,
      onMainAreaClick,
      onChevronMouseEnter,
      onChevronMouseLeave,
    },
    ref,
  ) => {
    const isHighlighted = isSelected || isOpen;

    const handleClick = () => {
      if (!isDisabled) {
        onMainAreaClick();
      }
    };

    return (
      <div
        ref={ref}
        className={cn(
          "comet-body-s group relative flex h-auto min-h-10 w-full gap-1 rounded-sm p-px",
          isDisabled && "opacity-50",
        )}
      >
        <div
          onClick={handleClick}
          className={cn(
            "flex flex-1 items-start gap-2 rounded px-2 py-2",
            isDisabled
              ? "cursor-not-allowed"
              : "cursor-pointer group-hover:bg-primary-foreground",
            isHighlighted && !isDisabled && "bg-primary-foreground",
          )}
        >
          <div className="mt-0.5 size-4 shrink-0">
            {isSelected && <Check className="size-4 text-muted-slate" />}
          </div>
          <TooltipWrapper content={dataset.name}>
            <div className="flex flex-col gap-0.5">
              <span className="max-w-[220px] truncate">{dataset.name}</span>
              {dataset.description && (
                <span className="comet-body-s max-w-[220px] text-light-slate">
                  {dataset.description}
                </span>
              )}
            </div>
          </TooltipWrapper>
        </div>

        {isDisabled ? (
          <div className="relative flex w-8 shrink-0 justify-center self-stretch rounded pt-3">
            <TooltipWrapper content={disabledTooltip}>
              <Info className="size-3.5 text-light-slate" />
            </TooltipWrapper>
          </div>
        ) : (
          showChevron && (
            <div
              onMouseEnter={onChevronMouseEnter}
              onMouseLeave={onChevronMouseLeave}
              className={cn(
                "relative flex w-8 shrink-0 justify-center self-stretch rounded pt-3",
                isHighlighted && "bg-primary-foreground",
              )}
            >
              <ChevronRight className="size-3.5 text-light-slate" />
            </div>
          )
        )}
      </div>
    );
  },
);

DatasetOption.displayName = "DatasetOption";

export default DatasetOption;
