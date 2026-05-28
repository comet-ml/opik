import React from "react";
import { ChevronDown } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";

export interface FilterOperatorOption<T extends string> {
  value: T;
  label: string;
}

interface FilterOperatorSelectProps<T extends string> {
  fieldLabel: string;
  value: T;
  options: FilterOperatorOption<T>[];
  onChange: (value: T) => void;
}

const lowerFirst = (text: string) =>
  text.charAt(0).toLowerCase() + text.slice(1);

export function FilterOperatorSelect<T extends string>({
  fieldLabel,
  value,
  options,
  onChange,
}: FilterOperatorSelectProps<T>) {
  const current = options.find((option) => option.value === value);

  return (
    <div className="flex items-center gap-0.5">
      <span className="comet-body-xs px-0.5 text-muted-slate">
        {fieldLabel}
      </span>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="comet-body-xs-accented flex items-center gap-1 rounded-[4px] px-0.5 text-foreground outline-none transition-colors hover:bg-muted data-[state=open]:bg-muted"
          >
            {current ? lowerFirst(current.label) : ""}
            <ChevronDown className="size-3 shrink-0" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start" className="min-w-[146px]">
          {options.map((option) => (
            <DropdownMenuItem
              key={option.value}
              size="sm"
              selected={option.value === value}
              onSelect={() => onChange(option.value)}
            >
              {option.label}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

export default FilterOperatorSelect;
