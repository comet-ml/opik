import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Command as CommandPrimitive } from "cmdk";
import { Popover, PopoverAnchor, PopoverContent } from "@/ui/popover";
import { Command, CommandItem, CommandList } from "@/ui/command";
import { cn } from "@/lib/utils";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import { filterItems, highlightMatch } from "./helpers";

interface AutocompleteProps {
  options: ChipOptionsResult;
  itemNoun: string;
  value?: string;
  onCommit: (next: string) => void;
  onPick?: (next: string) => void;
  commitOnBlur?: boolean;
  onEscape?: () => void;
  autoFocus?: boolean;
  children: React.ReactElement;
}

const Autocomplete: React.FC<AutocompleteProps> = ({
  options,
  itemNoun,
  value,
  onCommit,
  onPick,
  commitOnBlur = false,
  onEscape,
  autoFocus = false,
  children,
}) => {
  const [draft, setDraft] = useState(value ?? "");
  const [focused, setFocused] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { items, isLoading } = options;

  useEffect(() => {
    if (value !== undefined) setDraft(value);
  }, [value]);

  useEffect(() => {
    if (autoFocus) inputRef.current?.focus();
  }, [autoFocus]);

  const filtered = useMemo(() => filterItems(items, draft), [items, draft]);
  const hasQuery = draft.trim() !== "";
  const showResults = !isLoading && filtered.length > 0;
  const showNoMatch = hasQuery && !isLoading && filtered.length === 0;
  const popoverOpen = focused && (isLoading || showResults || showNoMatch);

  const commit = useCallback(
    (next: string) => {
      const trimmed = next.trim();
      if (trimmed === "") return;
      if (value !== undefined && trimmed === value.trim()) return;
      onCommit(trimmed);
    },
    [onCommit, value],
  );

  const pick = useCallback(
    (item: string) => {
      setDraft(item);
      commit(item);
      onPick?.(item);
      inputRef.current?.blur();
    },
    [commit, onPick],
  );

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Escape") {
      event.preventDefault();
      if (value !== undefined) setDraft(value);
      if (onEscape) onEscape();
      inputRef.current?.blur();
    }
  };

  const itemClass = cn(
    "comet-body-s rounded-[4px] px-2 py-1.5",
    "data-[selected=true]:bg-primary-foreground",
  );

  return (
    <Popover open={popoverOpen}>
      <Command shouldFilter={false}>
        <PopoverAnchor asChild>
          <CommandPrimitive.Input
            asChild
            ref={inputRef}
            value={draft}
            onValueChange={setDraft}
            onFocus={() => setFocused(true)}
            onBlur={() => {
              setFocused(false);
              if (commitOnBlur) commit(draft);
            }}
            onKeyDown={handleKeyDown}
          >
            {children}
          </CommandPrimitive.Input>
        </PopoverAnchor>
        {!popoverOpen && <CommandList aria-hidden="true" className="hidden" />}
        <PopoverContent
          asChild
          align="start"
          sideOffset={4}
          onOpenAutoFocus={(event) => event.preventDefault()}
          onInteractOutside={(event) => {
            if (event.target === inputRef.current) event.preventDefault();
          }}
        >
          <CommandList className="w-[--radix-popover-trigger-width] min-w-[320px] max-w-[800px] rounded-md border border-border bg-background p-1 shadow-md">
            {isLoading && (
              <div className="comet-body-s p-2 text-light-slate">Loading…</div>
            )}
            {showResults &&
              filtered.map((item) => (
                <CommandItem
                  key={item}
                  value={item}
                  onMouseDown={(event) => event.preventDefault()}
                  onSelect={() => pick(item)}
                  className={cn(itemClass, "text-foreground")}
                >
                  <span className="block break-all">
                    {highlightMatch(item, draft)}
                  </span>
                </CommandItem>
              ))}
            {showNoMatch && (
              <CommandItem
                value={draft}
                onMouseDown={(event) => event.preventDefault()}
                onSelect={() => {
                  commit(draft);
                  inputRef.current?.blur();
                }}
                className={cn(itemClass, "text-light-slate")}
              >
                <span>
                  No match in recent {itemNoun} — press Enter to search all
                </span>
              </CommandItem>
            )}
          </CommandList>
        </PopoverContent>
      </Command>
    </Popover>
  );
};

export default Autocomplete;
