import { useEffect, useMemo, useState } from "react";
import { Command as CommandPrimitive } from "cmdk";
import { Check } from "lucide-react";
import debounce from "lodash/debounce";

import { cn } from "@/lib/utils";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandItem,
  CommandList,
} from "@/components/ui/command";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Popover,
  PopoverAnchor,
  PopoverContent,
} from "@/components/ui/popover";

type Props<T extends string> = {
  value: T;
  onValueChange: (value: T) => void;
  items: T[];
  hasError?: boolean;
  isLoading?: boolean;
  emptyMessage?: string;
  placeholder?: string;
  delay?: number;
};

const AutoComplete = <T extends string>({
  value,
  onValueChange,
  items,
  hasError,
  isLoading,
  emptyMessage = "No items found",
  placeholder = "Search...",
  delay = 300,
}: Props<T>) => {
  const [open, setOpen] = useState(false);
  const [localValue, setLocalValue] = useState<T>();

  const handleDebouncedValueChange = useMemo(
    () => debounce(onValueChange, delay),
    [delay, onValueChange],
  );

  useEffect(() => {
    setLocalValue(value);
    return () => handleDebouncedValueChange.cancel();
  }, [handleDebouncedValueChange, value]);

  return (
    <div className="flex items-center">
      <Popover open={open} onOpenChange={setOpen} modal>
        <Command shouldFilter={false}>
          <PopoverAnchor asChild>
            <CommandPrimitive.Input
              asChild
              value={localValue}
              onValueChange={(v) => {
                setLocalValue(v as T);
                handleDebouncedValueChange(v as T);
              }}
              onKeyDown={(e) => setOpen(e.key !== "Escape")}
              onFocus={() => setOpen(true)}
            >
              <Input
                className={cn({ "border-destructive": hasError })}
                placeholder={placeholder}
              />
            </CommandPrimitive.Input>
          </PopoverAnchor>
          {!open && <CommandList aria-hidden="true" className="hidden" />}
          <PopoverContent
            asChild
            onOpenAutoFocus={(e) => e.preventDefault()}
            onInteractOutside={(e) => {
              if (
                e.target instanceof Element &&
                e.target.hasAttribute("cmdk-input")
              ) {
                e.preventDefault();
              }
            }}
            className="w-[--radix-popover-trigger-width] p-0"
          >
            <CommandList>
              {isLoading && (
                <CommandPrimitive.Loading>
                  <div className="p-1">
                    <Skeleton className="h-6 w-full" />
                  </div>
                </CommandPrimitive.Loading>
              )}
              {items.length > 0 && !isLoading ? (
                <CommandGroup>
                  {items.map((option) => (
                    <CommandItem
                      key={option}
                      value={option}
                      onMouseDown={(e) => e.preventDefault()}
                      onSelect={(inputValue: string) => {
                        onValueChange(inputValue as T);
                        setOpen(false);
                      }}
                    >
                      <Check
                        className={cn(
                          "size-4",
                          value === option ? "opacity-100" : "opacity-0",
                        )}
                      />
                      {option}
                    </CommandItem>
                  ))}
                </CommandGroup>
              ) : null}
              {!isLoading ? <CommandEmpty>{emptyMessage}</CommandEmpty> : null}
            </CommandList>
          </PopoverContent>
        </Command>
      </Popover>
    </div>
  );
};

export default AutoComplete;
