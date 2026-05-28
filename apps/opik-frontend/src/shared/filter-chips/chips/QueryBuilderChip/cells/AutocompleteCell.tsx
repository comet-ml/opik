import React from "react";
import { cn } from "@/lib/utils";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import Autocomplete from "../Autocomplete/Autocomplete";
import { cellInput } from "./cellBase";

interface AutocompleteCellProps {
  value: string;
  placeholder: string;
  options: ChipOptionsResult;
  itemNoun: string;
  onChange: (next: string) => void;
  onPick?: (next: string) => void;
  autoFocus?: boolean;
  grow?: boolean;
  hasError?: boolean;
}

export const AutocompleteCell: React.FC<AutocompleteCellProps> = ({
  value,
  placeholder,
  options,
  itemNoun,
  onChange,
  onPick,
  autoFocus = false,
  grow = false,
  hasError = false,
}) => (
  <Autocomplete
    options={options}
    itemNoun={itemNoun}
    value={value}
    onCommit={onChange}
    onPick={onPick}
    autoFocus={autoFocus}
    commitOnBlur
  >
    <input
      type="text"
      data-filter-cell
      placeholder={placeholder}
      className={cn(
        cellInput,
        grow && "flex-1",
        hasError && "border-destructive focus:border-destructive",
      )}
    />
  </Autocomplete>
);
