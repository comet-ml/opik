import React from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";

interface FilterRowProps {
  onRemove: () => void;
  disableRemove?: boolean;
  children: React.ReactNode;
  className?: string;
}

const ROW_CLASS =
  "flex w-full items-center gap-0.5 rounded-[4px] bg-primary-100 px-1 py-0.5";

const CELLS_CLASS = cn(
  "flex min-w-0 flex-1 items-center",
  "[&>*:first-child[data-filter-cell]]:rounded-l-[2px] [&>*:first-child_[data-filter-cell]]:rounded-l-[2px]",
  "[&>*:last-child[data-filter-cell]]:rounded-r-[2px] [&>*:last-child_[data-filter-cell]]:rounded-r-[2px]",
);

/** A row of filter cells with no remove button, for a filter that is removed as a whole elsewhere. */
export const InlineFilterRow: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => (
  <div className={ROW_CLASS}>
    <div className={CELLS_CLASS}>{children}</div>
  </div>
);

export const FilterRow: React.FC<FilterRowProps> = ({
  onRemove,
  disableRemove = false,
  children,
  className,
}) => (
  <div className={cn(ROW_CLASS, className)}>
    <div className={CELLS_CLASS}>{children}</div>
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
