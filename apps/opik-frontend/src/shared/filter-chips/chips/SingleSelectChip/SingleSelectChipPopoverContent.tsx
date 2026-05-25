import React, { useMemo, useState } from "react";
import { Check, Search, X } from "lucide-react";
import {
  SingleSelectChipDefinition,
  SingleSelectChipValue,
} from "@/shared/filter-chips/types";
import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface SingleSelectChipPopoverContentProps {
  definition: SingleSelectChipDefinition;
  value: SingleSelectChipValue | undefined;
  onSelect: (value: SingleSelectChipValue) => void;
  onClear: () => void;
}

const SEARCH_OPTION_THRESHOLD = 5;

const SingleSelectChipPopoverContent: React.FC<
  SingleSelectChipPopoverContentProps
> = ({ definition, value, onSelect, onClear }) => {
  const [search, setSearch] = useState("");
  const showSearch = definition.options.length > SEARCH_OPTION_THRESHOLD;

  const filteredOptions = useMemo(() => {
    if (!showSearch || search.trim() === "") return definition.options;
    const needle = search.trim().toLowerCase();
    return definition.options.filter((option) =>
      option.label.toLowerCase().includes(needle),
    );
  }, [definition.options, search, showSearch]);

  return (
    <div className="flex w-[238px] flex-col p-1">
      {showSearch && (
        <div className="relative p-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-slate" />
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search"
            className="h-8 pl-8"
            autoFocus
          />
        </div>
      )}
      {filteredOptions.length === 0 && (
        <div className="px-4 py-2 text-sm text-muted-slate">No results</div>
      )}
      {filteredOptions.map((option) => {
        const isSelected = value?.value === option.value;
        return (
          <div
            key={option.value}
            role="button"
            tabIndex={0}
            onClick={() => {
              if (isSelected) return;
              onSelect({ value: option.value });
            }}
            onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                if (!isSelected) onSelect({ value: option.value });
              }
            }}
            className={cn(
              "group flex h-10 min-w-0 cursor-pointer items-center justify-between gap-2 rounded-[4px] px-4 outline-none",
              "hover:bg-primary-foreground focus-visible:bg-primary-foreground",
              isSelected && "bg-primary-foreground",
            )}
          >
            <div className="flex min-w-0 flex-1 items-center gap-2">
              {isSelected && (
                <Check className="size-4 shrink-0 text-foreground" />
              )}
              <TooltipWrapper content={option.label}>
                <span
                  className={cn(
                    "truncate text-sm text-foreground",
                    isSelected ? "font-medium" : "font-normal",
                  )}
                >
                  {option.label}
                </span>
              </TooltipWrapper>
            </div>
            {isSelected && (
              <TooltipWrapper content="Clear filter">
                <button
                  type="button"
                  aria-label="Clear filter"
                  onClick={(event) => {
                    event.stopPropagation();
                    onClear();
                  }}
                  className="hidden size-4 items-center justify-center text-muted-slate group-hover:flex group-focus-visible:flex"
                >
                  <X className="size-4" />
                </button>
              </TooltipWrapper>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default SingleSelectChipPopoverContent;
