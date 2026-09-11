import React from "react";
import { ChevronDown, LucideIcon } from "lucide-react";

import { cn } from "@/lib/utils";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";

type Option<T extends string> = {
  value: T;
  label: string;
  icon?: LucideIcon;
};

type FormFieldModeSelectProps<T extends string> = {
  value: T;
  options: Option<T>[];
  onChange: (value: T) => void;
};

/**
 * Visual sibling of the trace-side `CodeBlockModeSelect`. Uses a dropdown menu
 * (rather than radix Select) so it composes naturally with our other form
 * dropdowns, but keeps the same compact trigger styling.
 */
const FormFieldModeSelect = <T extends string>({
  value,
  options,
  onChange,
}: FormFieldModeSelectProps<T>) => {
  const selected = options.find((o) => o.value === value);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className={cn(
          "group flex h-6 items-center gap-1 rounded px-2 py-0.5",
          "comet-body-xs text-muted-slate transition-colors hover:text-foreground",
          "focus:outline-none focus-visible:ring-2 focus-visible:ring-primary",
        )}
      >
        <span className="truncate">{selected?.label ?? value}</span>
        {selected?.icon && <selected.icon className="size-3 shrink-0" />}
        <ChevronDown className="size-3 shrink-0 text-light-slate transition-transform duration-200 group-data-[state=open]:rotate-180" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {options.map((opt) => (
          <DropdownMenuItem
            key={opt.value}
            size="sm"
            selected={opt.value === value}
            onClick={() => onChange(opt.value)}
          >
            {opt.label}
            {opt.icon && <opt.icon className="ml-1 size-3" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default FormFieldModeSelect;
