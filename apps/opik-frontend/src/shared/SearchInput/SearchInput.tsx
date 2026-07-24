import { forwardRef } from "react";
import { Search, X } from "lucide-react";

import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import { InputProps } from "@/ui/input";
import { Button, ButtonProps } from "@/ui/button";
import { cn } from "@/lib/utils";

const SEARCH_TEXT_DELAY = 300;

type SearchInputDimension = NonNullable<InputProps["dimension"]>;

type SearchInputStyle = {
  iconWrapper: string;
  icon: string;
  input: string;
  clearButton: ButtonProps["size"];
};

const DEFAULT_SEARCH_STYLE: SearchInputStyle = {
  iconWrapper: "left-2.5",
  icon: "size-3.5",
  input: "px-8",
  clearButton: "icon-xs",
};

const SEARCH_STYLE_BY_DIMENSION: Partial<
  Record<SearchInputDimension, SearchInputStyle>
> = {
  xs: {
    iconWrapper: "left-2",
    icon: "size-3",
    input: "rounded-sm pl-7 pr-7",
    clearButton: "icon-2xs",
  },
};

export type SearchInputProps = {
  searchText?: string;
  setSearchText: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  dimension?: InputProps["dimension"];
  variant?: "default" | "ghost";
  disableDebounce?: boolean;
};

export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(
  (
    {
      searchText = "",
      setSearchText,
      placeholder = "Search",
      disabled = false,
      className,
      dimension,
      variant = "default",
      disableDebounce = false,
    },
    ref,
  ) => {
    const style =
      (dimension && SEARCH_STYLE_BY_DIMENSION[dimension]) ??
      DEFAULT_SEARCH_STYLE;
    return (
      <div className={cn("relative w-full", className)}>
        <div
          className={cn("absolute top-1/2 -translate-y-1/2", style.iconWrapper)}
        >
          <Search className={cn("text-light-slate", style.icon)} />
        </div>
        <DebounceInput
          ref={ref}
          className={style.input}
          delay={disableDebounce ? 0 : SEARCH_TEXT_DELAY}
          onValueChange={setSearchText as (value: unknown) => void}
          placeholder={placeholder}
          disabled={disabled}
          value={searchText}
          variant={variant}
          dimension={dimension}
          data-testid="search-input"
        />
        {searchText !== "" && (
          <div className="absolute right-1 top-1/2 -translate-y-1/2">
            <Button
              variant="minimal"
              size={style.clearButton}
              onClick={() => setSearchText("")}
            >
              <X />
            </Button>
          </div>
        )}
      </div>
    );
  },
);

SearchInput.displayName = "SearchInput";

export default SearchInput;
