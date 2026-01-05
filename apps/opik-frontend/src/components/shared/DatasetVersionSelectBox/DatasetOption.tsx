import React from "react";
import { ChevronRight, Check } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Dataset } from "@/types/datasets";

interface DatasetOptionProps {
  dataset: Dataset;
  workspaceName: string;
  isSelected: boolean;
  isOpen: boolean;
  onMouseEnter: () => void;
  onMouseLeave: () => void;
}

const DatasetOption = React.forwardRef<HTMLDivElement, DatasetOptionProps>(
  ({ dataset, isSelected, isOpen, onMouseEnter, onMouseLeave }, ref) => {
    return (
      <div
        ref={ref}
        onMouseEnter={onMouseEnter}
        onMouseLeave={onMouseLeave}
        className={cn(
          "comet-body-s group relative flex min-h-10 h-auto w-full cursor-pointer gap-2 rounded-sm px-2 pl-12 py-2 hover:bg-primary-foreground/80",
          {
            "bg-primary-foreground/50": isOpen,
            "bg-primary-foreground": isSelected,
          },
        )}
      >
        {isSelected && (
          <Check className="absolute left-5 top-3 size-4 text-muted-slate" />
        )}
        <TooltipWrapper content={dataset.name}>
          <div className="mt-0.5 flex flex-col gap-0.5">
            <span className="flex-1 truncate">{dataset.name}</span>
            <span className="comet-body-s flex max-w-[220px] text-light-slate">
              {dataset.description}
            </span>
          </div>
        </TooltipWrapper>
        <ChevronRight className="ml-auto mr-3 mt-1 size-4 shrink-0 text-light-slate" />
      </div>
    );
  },
);

DatasetOption.displayName = "DatasetOption";

export default DatasetOption;
