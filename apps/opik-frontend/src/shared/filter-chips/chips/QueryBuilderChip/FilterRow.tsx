import React from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

interface FilterRowProps {
  onRemove: () => void;
  disableRemove?: boolean;
  children: React.ReactNode;
  className?: string;
}

export const FilterRow: React.FC<FilterRowProps> = ({
  onRemove,
  disableRemove = false,
  children,
  className,
}) => (
  <div
    className={cn(
      "flex w-full items-center gap-0.5 rounded-[4px] bg-primary-100 px-1 py-0.5",
      className,
    )}
  >
    <div
      className={cn(
        "flex min-w-0 flex-1 items-center",
        "[&>*:first-child[data-filter-cell]]:rounded-l-[2px] [&>*:first-child_[data-filter-cell]]:rounded-l-[2px]",
        "[&>*:last-child[data-filter-cell]]:rounded-r-[2px] [&>*:last-child_[data-filter-cell]]:rounded-r-[2px]",
      )}
    >
      {children}
    </div>
    <button
      type="button"
      aria-label="Remove filter"
      onClick={onRemove}
      disabled={disableRemove}
      className={cn(
        "flex shrink-0 items-center justify-center transition-colors",
        disableRemove
          ? "cursor-not-allowed text-muted-slate/40"
          : "text-muted-slate hover:text-foreground",
      )}
    >
      <X className="size-3" />
    </button>
  </div>
);
