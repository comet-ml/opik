import React from "react";
import { Check, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Dataset } from "@/types/datasets";

interface DatasetOptionProps {
  dataset: Dataset;
  isSelected: boolean;
  isOpen: boolean;
  showChevron: boolean;
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
      onMainAreaClick,
      onChevronMouseEnter,
      onChevronMouseLeave,
    },
    ref,
  ) => {
    const isHighlighted = isSelected || isOpen;

    return (
      <div
        ref={ref}
        className="comet-body-s group relative flex h-auto min-h-10 w-full gap-1 rounded-sm p-px"
      >
        <div
          onClick={onMainAreaClick}
          className={cn(
            "flex flex-1 cursor-pointer items-start gap-2 rounded px-2 py-2 group-hover:bg-primary-foreground",
            isHighlighted && "bg-primary-foreground",
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

        {showChevron && (
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
        )}
      </div>
    );
  },
);

DatasetOption.displayName = "DatasetOption";

export default DatasetOption;
