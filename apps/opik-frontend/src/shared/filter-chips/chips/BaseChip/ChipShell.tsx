import React, { forwardRef } from "react";
import { cn } from "@/lib/utils";

export interface ChipShellProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  applied?: boolean;
  isOpen?: boolean;
}

const ChipShell = forwardRef<HTMLButtonElement, ChipShellProps>(
  ({ applied, isOpen, disabled, className, children, ...rest }, ref) => (
    <button
      ref={ref}
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
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  ),
);

ChipShell.displayName = "ChipShell";

export default ChipShell;
