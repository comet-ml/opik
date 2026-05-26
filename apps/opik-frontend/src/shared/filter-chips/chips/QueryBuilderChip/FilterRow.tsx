import React from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import { cellButton } from "./cells/cellBase";

interface FilterRowProps {
  onRemove: () => void;
  children: React.ReactNode;
  className?: string;
}

export const FilterRow: React.FC<FilterRowProps> = ({
  onRemove,
  children,
  className,
}) => (
  <div
    className={cn(
      "flex w-full items-center rounded-[4px]",
      "[&>*:first-child]:rounded-l-[2px]",
      "[&>*:last-child]:rounded-r-[2px]",
      "[&>*:not(:first-child)]:-ml-px",
      className,
    )}
  >
    {children}
    <button
      type="button"
      aria-label="Remove filter"
      onClick={onRemove}
      className={cn(cellButton, "justify-center px-1.5 text-light-slate")}
    >
      <X className="size-3" />
    </button>
  </div>
);
