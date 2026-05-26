import React, { forwardRef } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ChipShell from "@/shared/filter-chips/chips/BaseChip/ChipShell";

export interface BaseChipProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  label: string;
  valueSummary?: string | null;
  valueSummaryFull?: string | null;
  isOpen?: boolean;
}

const BaseChip = forwardRef<HTMLButtonElement, BaseChipProps>(
  ({ label, valueSummary, valueSummaryFull, isOpen = false, ...rest }, ref) => {
    const isApplied = Boolean(valueSummary);
    const ChevronIcon = isOpen ? ChevronUp : ChevronDown;
    const tooltip = isApplied
      ? `${label}: ${valueSummaryFull ?? valueSummary}`
      : null;

    return (
      <ChipShell
        ref={ref}
        applied={isApplied}
        isOpen={isOpen}
        aria-expanded={isOpen}
        {...rest}
      >
        <TooltipWrapper content={tooltip}>
          <span className="truncate">
            {label}
            {isApplied && <>: {valueSummary}</>}
          </span>
        </TooltipWrapper>
        <ChevronIcon className="size-3 shrink-0" />
      </ChipShell>
    );
  },
);

BaseChip.displayName = "BaseChip";

export default BaseChip;
