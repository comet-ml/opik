import React, { forwardRef } from "react";
import { ChevronDown, ChevronUp } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export interface BaseChipProps extends React.HTMLAttributes<HTMLButtonElement> {
  label: string;
  valueSummary?: string | null;
  valueSummaryFull?: string | null;
  isOpen?: boolean;
  disabled?: boolean;
}

const BaseChip = forwardRef<HTMLButtonElement, BaseChipProps>(
  (
    {
      label,
      valueSummary,
      valueSummaryFull,
      isOpen = false,
      disabled,
      className,
      ...rest
    },
    ref,
  ) => {
    const isApplied = Boolean(valueSummary);
    const ChevronIcon = isOpen ? ChevronUp : ChevronDown;
    const tooltip = isApplied
      ? `${label}: ${valueSummaryFull ?? valueSummary}`
      : null;

    return (
      <button
        ref={ref}
        type="button"
        disabled={disabled}
        aria-expanded={isOpen}
        className={cn(
          "flex h-6 max-w-[280px] shrink-0 items-center gap-1 rounded-[20px] border border-solid pl-2 pr-1.5 py-0.5 outline-none transition-colors",
          "comet-body-xs-accented",
          isOpen
            ? "border-secondary bg-primary-100 text-primary-active"
            : isApplied
              ? "border-secondary bg-primary-100/50 text-primary-active hover:bg-primary-100 hover:text-primary-hover"
              : "border-border bg-soft-background text-muted-slate hover:border-secondary hover:bg-primary-100 hover:text-primary-hover",
          "focus-visible:ring-2 focus-visible:ring-primary-active/40",
          disabled && "cursor-not-allowed opacity-50",
          className,
        )}
        {...rest}
      >
        <TooltipWrapper content={tooltip}>
          <span className="truncate">
            {label}
            {isApplied && <>: {valueSummary}</>}
          </span>
        </TooltipWrapper>
        <ChevronIcon className="size-3 shrink-0" />
      </button>
    );
  },
);

BaseChip.displayName = "BaseChip";

export default BaseChip;
