import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Command as CommandPrimitive } from "cmdk";
import debounce from "lodash/debounce";
import isFunction from "lodash/isFunction";

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
  const [localValue, setLocalValue] = useState<T | undefined>(
    value || ("" as T),
  );

  const isFocusedRef = useRef(false);
  const pendingValueRef = useRef<T | undefined>(undefined);
  const valueChangeCallbackRef = useRef<((value: T) => void) | undefined>(
    onValueChange,
  );

  useEffect(() => {
    valueChangeCallbackRef.current = onValueChange;
  }, [onValueChange]);

  const handleDebouncedValueChange = useMemo(
    () =>
      debounce((val: T) => {
        isFunction(valueChangeCallbackRef.current) &&
          valueChangeCallbackRef.current(val);
      }, delay),
    [delay],
  );

  useEffect(() => {
    if (!isFocusedRef.current) {
      setLocalValue(value);
    } else {
      pendingValueRef.current = value;
    }
  }, [value]);

  useEffect(() => {
    return () => {
      handleDebouncedValueChange.cancel();
    };
  }, [handleDebouncedValueChange]);

  const handleValueChange = useCallback(
    (newValue: string) => {
      const typedValue = newValue as T;
      setLocalValue(typedValue);
      handleDebouncedValueChange(typedValue);
    },
    [handleDebouncedValueChange],
  );

  const handleFocus = useCallback(() => {
    isFocusedRef.current = true;
    setOpen(true);
  }, []);

  const handleBlur = useCallback(() => {
    isFocusedRef.current = false;

    handleDebouncedValueChange.flush();

    setLocalValue((state) => {
      if (state !== pendingValueRef.current) {
        const newValue = pendingValueRef.current;
        pendingValueRef.current = undefined;
        return newValue;
      }

      return state;
    });
  }, [handleDebouncedValueChange]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      setOpen(false);
      if (e.target && "blur" in e.target) {
        (e.target as HTMLElement).blur();
      }
    } else {
      setOpen(true);
    }
  }, []);

  const displayValue = localValue ?? value ?? ("" as T);

  return (
    <div className="flex items-center">
      <Popover open={open} onOpenChange={setOpen}>
        <Command shouldFilter={false}>
          <PopoverAnchor asChild>
            <CommandPrimitive.Input
              asChild
              value={displayValue}
              onValueChange={handleValueChange}
              onKeyDown={handleKeyDown}
              onFocus={handleFocus}
              onBlur={handleBlur}
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
            <CommandList onWheel={(e) => e.stopPropagation()}>
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
                        const selectedValue = inputValue as T;
                        setLocalValue(selectedValue);
                        onValueChange(selectedValue);
                        setOpen(false);
                      }}
                    >
                      <span className="truncate">{option}</span>
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
