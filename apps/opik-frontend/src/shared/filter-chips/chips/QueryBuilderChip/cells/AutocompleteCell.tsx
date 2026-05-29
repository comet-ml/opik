import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Command as CommandPrimitive } from "cmdk";
import { Popover, PopoverAnchor, PopoverContent } from "@/ui/popover";
import { Command, CommandGroup, CommandItem, CommandList } from "@/ui/command";
import { cn } from "@/lib/utils";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
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

const filterItems = (items: string[], query: string): string[] => {
  const q = query.trim().toLowerCase();
  if (q === "") return items;
  return items.filter((item) => item.toLowerCase().includes(q));
};

const highlightMatch = (text: string, query: string): React.ReactNode => {
  if (query === "") return text;
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  const idx = lower.indexOf(q);
  if (idx === -1) return text;
  return (
    <>
      {text.slice(0, idx)}
      <span className="font-medium text-primary-active">
        {text.slice(idx, idx + query.length)}
      </span>
      {text.slice(idx + query.length)}
    </>
  );
};

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
}) => {
  const [draft, setDraft] = useState(value);
  const [focused, setFocused] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const { items, isLoading } = options;

  useEffect(() => {
    setDraft(value);
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
      if (trimmed === value.trim()) return;
      onChange(trimmed);
    },
    [onChange, value],
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
      setDraft(value);
      inputRef.current?.blur();
    }
  };

  const itemClass = cn(
    "comet-body-s-accented flex h-8 items-center rounded-[4px] px-4",
    "data-[selected=true]:bg-primary-foreground",
  );

  return (
    <Popover open={popoverOpen}>
      <Command shouldFilter={false} className="contents">
        <PopoverAnchor asChild>
          <CommandPrimitive.Input
            asChild
            ref={inputRef}
            value={draft}
            onValueChange={setDraft}
            onFocus={() => setFocused(true)}
            onBlur={() => {
              setFocused(false);
              commit(draft);
            }}
            onKeyDown={handleKeyDown}
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
            {showResults && (
              <CommandGroup
                heading="Recently used"
                className="p-0 [&_[cmdk-group-heading]]:px-4 [&_[cmdk-group-heading]]:py-2 [&_[cmdk-group-heading]]:text-light-slate"
              >
                {filtered.map((item) => (
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
              </CommandGroup>
            )}
            {showNoMatch && (
              <CommandItem
                value={draft}
                onMouseDown={(event) => event.preventDefault()}
                onSelect={() => pick(draft)}
                className={cn(
                  itemClass,
                  "h-auto min-h-8 py-1.5 font-normal text-light-slate data-[selected=true]:text-light-slate",
                )}
              >
                <span>
                  No match in recent {itemNoun} - type your {itemNoun} to search
                  all
                </span>
              </CommandItem>
            )}
          </CommandList>
        </PopoverContent>
      </Command>
    </Popover>
  );
};
