import React, { forwardRef } from "react";
import { CircleX } from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

export interface ChipShellProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  applied?: boolean;
  isOpen?: boolean;
  onClear?: () => void;
}

const ChipShell = forwardRef<HTMLElement, ChipShellProps>(
  (
    { applied, isOpen, onClear, disabled, className, children, ...rest },
    ref,
  ) => {
    const showClear = Boolean(applied && onClear && !isOpen && !disabled);

    const bodyButton = (
      <button
        ref={showClear ? undefined : (ref as React.Ref<HTMLButtonElement>)}
        type="button"
        disabled={disabled}
        className={cn(
          "flex h-6 max-w-[280px] shrink-0 items-center gap-1 rounded-[20px] border border-solid pl-2 pr-1.5 py-0.5 outline-none transition-colors",
          "comet-body-xs-accented",
          isOpen
            ? "border-secondary bg-primary-100 text-primary-active"
            : applied
              ? "border-secondary bg-primary-100/50 text-primary-active hover:bg-primary-100 hover:text-primary-hover"
              : "border-border bg-soft-background text-muted-slate hover:border-secondary hover:bg-primary-100 hover:text-primary-hover",
          "focus-visible:ring-2 focus-visible:ring-primary-active/40",
          disabled && "cursor-not-allowed opacity-50",
          showClear && "group-hover:rounded-r-none group-hover:border-r-0",
          className,
        )}
        {...rest}
      >
        {children}
      </button>
    );

    if (!showClear) return bodyButton;

    return (
      <span
        ref={ref as React.Ref<HTMLSpanElement>}
        className="group inline-flex items-center"
      >
        {bodyButton}
        <TooltipWrapper content="Clear">
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation();
              onClear?.();
            }}
            aria-label="Clear filter"
            className={cn(
              "hidden h-6 items-center justify-center rounded-r-[20px] border border-l-0 border-secondary bg-primary-100/50 pl-1 pr-1.5 text-primary-active outline-none transition-colors group-hover:inline-flex",
              "hover:bg-primary-100 hover:text-primary-hover",
              "focus-visible:ring-2 focus-visible:ring-primary-active/40",
            )}
          >
            <CircleX className="size-3 shrink-0" />
          </button>
        </TooltipWrapper>
      </span>
    );
  },
);

ChipShell.displayName = "ChipShell";

export default ChipShell;
