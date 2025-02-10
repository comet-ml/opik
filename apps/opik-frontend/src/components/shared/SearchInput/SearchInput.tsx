import React from "react";

import { Search } from "lucide-react";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { cn } from "@/lib/utils";

const SEARCH_TEXT_DELAY = 300;

export type SearchInputProps = {
  searchText?: string;
  setSearchText: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  variant?: "default" | "ghost";
};

export const SearchInput = ({
  searchText = "",
  setSearchText,
  placeholder = "Search",
  disabled = false,
  className,
  variant = "default",
}: SearchInputProps) => {
  return (
    <div className={cn("relative w-full", className)}>
      <div className="absolute left-3 top-1/2 -translate-y-1/2">
        <Search className="size-4 text-muted-slate" />
      </div>
      <DebounceInput
        className="pl-9"
        delay={SEARCH_TEXT_DELAY}
        onValueChange={setSearchText as (value: unknown) => void}
        placeholder={placeholder}
        disabled={disabled}
        value={searchText}
        variant={variant}
        data-testid="search-input"
      />
    </div>
  );
};

export default SearchInput;
