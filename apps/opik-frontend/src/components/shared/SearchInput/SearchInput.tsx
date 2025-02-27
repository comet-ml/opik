import React from "react";
import { Search } from "lucide-react";

import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { InputProps } from "@/components/ui/input";
import { cn } from "@/lib/utils";

const SEARCH_TEXT_DELAY = 300;

export type SearchInputProps = {
  searchText?: string;
  setSearchText: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  dimension?: InputProps["dimension"];
  variant?: "default" | "ghost";
};

export const SearchInput = ({
  searchText = "",
  setSearchText,
  placeholder = "Search",
  disabled = false,
  className,
  dimension,
  variant = "default",
}: SearchInputProps) => {
  return (
    <div className={cn("relative w-full", className)}>
      <div className="absolute left-2.5 top-1/2 -translate-y-1/2">
        <Search className="size-3.5 text-muted-slate" />
      </div>
      <DebounceInput
        className="pl-8"
        delay={SEARCH_TEXT_DELAY}
        onValueChange={setSearchText as (value: unknown) => void}
        placeholder={placeholder}
        disabled={disabled}
        value={searchText}
        variant={variant}
        dimension={dimension}
        data-testid="search-input"
      />
    </div>
  );
};

export default SearchInput;
