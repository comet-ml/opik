import React from "react";
import { PopoverContent } from "@/ui/popover";
import { cn } from "@/lib/utils";
import { useAutocompleteContext } from "./AutocompleteContext";
import { highlightMatch } from "./helpers";

interface AutocompleteContentProps {
  className?: string;
}

export const AutocompleteContent: React.FC<AutocompleteContentProps> = ({
  className,
}) => {
  const {
    draft,
    filtered,
    isLoading,
    showResults,
    showNoMatch,
    itemNoun,
    inputRef,
    pick,
  } = useAutocompleteContext();

  return (
    <PopoverContent
      align="start"
      sideOffset={4}
      onOpenAutoFocus={(event) => event.preventDefault()}
      onInteractOutside={(event) => {
        if (event.target === inputRef.current) event.preventDefault();
      }}
      className={cn(
        "w-[--radix-popover-trigger-width] min-w-[320px] max-w-[800px] rounded-md border border-border bg-background p-1 shadow-md",
        className,
      )}
    >
      {isLoading && (
        <div className="comet-body-s p-2 text-light-slate">Loading…</div>
      )}
      {showResults && (
        <ul className="flex max-h-[280px] flex-col overflow-y-auto">
          {filtered.map((item) => (
            <li key={item}>
              <button
                type="button"
                onMouseDown={(event) => {
                  event.preventDefault();
                  pick(item);
                }}
                className={cn(
                  "comet-body-s flex w-full items-center rounded-[4px] px-2 py-1.5 text-left text-foreground outline-none",
                  "hover:bg-primary-foreground focus-visible:bg-primary-foreground",
                )}
              >
                <span className="block break-all">
                  {highlightMatch(item, draft)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {showNoMatch && (
        <div className="comet-body-s p-2 text-light-slate">
          No match in recent {itemNoun} — type your {itemNoun} to search all
        </div>
      )}
    </PopoverContent>
  );
};
