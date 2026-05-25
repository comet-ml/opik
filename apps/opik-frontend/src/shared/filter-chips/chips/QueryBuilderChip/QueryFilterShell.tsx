import React, { useState } from "react";
import { Plus, X } from "lucide-react";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { cn } from "@/lib/utils";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import Autocomplete from "./Autocomplete/Autocomplete";
import { FilterRow } from "./FilterRow";

interface QueryFilterShellProps<TRow> {
  rows: TRow[];
  renderRow: (row: TRow, index: number) => React.ReactNode;
  isRowApplied: (row: TRow) => boolean;
  onRemoveRow: (index: number) => void;
  onAddRow: (pickedValue: string) => void;
  onClearAll: () => void;
  searchPlaceholder: string;
  searchOptions: ChipOptionsResult;
  searchItemNoun: string;
  addLabel: string;
}

export function QueryFilterShell<TRow>({
  rows,
  renderRow,
  isRowApplied,
  onRemoveRow,
  onAddRow,
  onClearAll,
  searchPlaceholder,
  searchOptions,
  searchItemNoun,
  addLabel,
}: QueryFilterShellProps<TRow>) {
  const [searchOpen, setSearchOpen] = useState(false);

  const hasRows = rows.length > 0;
  const appliedCount = rows.filter(isRowApplied).length;
  const showSearch = !hasRows || searchOpen;

  const handlePick = (value: string) => {
    onAddRow(value);
    setSearchOpen(false);
  };

  return (
    <div
      className={cn("flex flex-col gap-2 p-3", "min-w-[360px] max-w-[800px]")}
    >
      {hasRows && (
        <ul className="flex flex-col gap-2">
          {rows.map((row, index) => (
            <li key={index}>
              <FilterRow onRemove={() => onRemoveRow(index)}>
                {renderRow(row, index)}
              </FilterRow>
            </li>
          ))}
        </ul>
      )}

      {showSearch ? (
        <div className="flex items-center gap-1">
          <Autocomplete
            options={searchOptions}
            itemNoun={searchItemNoun}
            onCommit={handlePick}
            autoFocus
            onEscape={hasRows ? () => setSearchOpen(false) : undefined}
          >
            <Autocomplete.Anchor>
              <Input dimension="sm" placeholder={searchPlaceholder} />
            </Autocomplete.Anchor>
            <Autocomplete.Content />
          </Autocomplete>
          {hasRows && (
            <button
              type="button"
              aria-label="Cancel"
              onClick={() => setSearchOpen(false)}
              className="flex size-6 shrink-0 items-center justify-center rounded text-light-slate hover:text-foreground"
            >
              <X className="size-4" />
            </button>
          )}
        </div>
      ) : (
        <Button
          variant="minimal"
          size="2xs"
          onClick={() => setSearchOpen(true)}
          className="self-start px-0"
        >
          <Plus className="mr-1 size-3" />
          {addLabel}
        </Button>
      )}

      {hasRows && (
        <div className="flex flex-col">
          <div className="border-t border-border" />
          <div className="flex items-center pt-2">
            <Button variant="minimal" size="2xs" onClick={onClearAll}>
              Clear all{appliedCount > 0 ? ` (${appliedCount})` : ""}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
