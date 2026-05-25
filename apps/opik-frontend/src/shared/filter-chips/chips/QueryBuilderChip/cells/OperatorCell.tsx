import React, { useState } from "react";
import { ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { cellButton } from "./cellBase";

interface OperatorOption<T extends string> {
  label: string;
  value: T;
}

interface OperatorCellProps<T extends string> {
  value: T;
  options: OperatorOption<T>[];
  onSelect: (value: T) => void;
  ariaLabel?: string;
}

export function OperatorCell<T extends string>({
  value,
  options,
  onSelect,
  ariaLabel,
}: OperatorCellProps<T>) {
  const [open, setOpen] = useState(false);
  const current = options.find((o) => o.value === value);

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button type="button" className={cellButton} aria-label={ariaLabel}>
          <span>{current?.label ?? value}</span>
          <ChevronDown className="size-3 shrink-0" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={4}
        className="w-auto min-w-[160px] rounded-md border border-border bg-background p-1 shadow-sm"
      >
        <ul className="flex flex-col">
          {options.map((option) => (
            <li key={option.value}>
              <button
                type="button"
                onClick={() => {
                  onSelect(option.value);
                  setOpen(false);
                }}
                className={cn(
                  "comet-body-s flex h-10 w-full min-w-[160px] items-center rounded-[4px] px-4 text-left text-foreground outline-none",
                  "hover:bg-primary-foreground focus-visible:bg-primary-foreground",
                  option.value === value && "font-medium",
                )}
              >
                {option.label}
              </button>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  );
}
