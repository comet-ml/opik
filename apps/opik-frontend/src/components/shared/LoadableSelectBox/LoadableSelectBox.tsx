import React, { useCallback, useMemo, useState } from "react";
import isFunction from "lodash/isFunction";
import toLower from "lodash/toLower";
import { Check, ChevronDown } from "lucide-react";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";
import NoOptions from "./NoOptions";
import SearchInput from "@/components/shared/SearchInput/SearchInput";

export type LoadableSelectBoxProps = {
  value?: string;
  placeholder?: string;
  onChange: (value: string) => void;
  options: DropdownOption<string>[];
  widthClass?: string;
  variant?: "outline" | "ghost";
  optionsCount?: number;
  isLoading?: boolean;
  onLoadMore?: () => void;
};

export const LoadableSelectBox = ({
  value = "",
  placeholder = "Select value",
  onChange,
  options,
  widthClass = "w-full",
  optionsCount = 25,
  isLoading = false,
  onLoadMore,
}: LoadableSelectBoxProps) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [width, setWidth] = useState<number | undefined>();

  const hasMore = isFunction(onLoadMore);

  const noDataText = search
    ? hasMore
      ? `No search results the first ${optionsCount} items`
      : "No search results"
    : "No data";
  const title = options.find((o) => o.value === value)?.label;

  const filteredOptions = useMemo(() => {
    return options.filter((o) => toLower(o.label).includes(toLower(search)));
  }, [options, search]);

  const openChangeHandler = useCallback((open: boolean) => {
    if (!open) {
      setSearch("");
    }
    setOpen(open);
  }, []);

  const onChangeRef = useCallback((node: HTMLButtonElement) => {
    if (!node) return null;

    const resizeObserver = new ResizeObserver(() => {
      window.requestAnimationFrame(() => {
        if (node) {
          setWidth(node.clientWidth);
        }
      });
    });

    resizeObserver.observe(node);

    return () => {
      resizeObserver.disconnect();
    };
  }, []);

  const hasFilteredOptions = Boolean(filteredOptions.length);
  const hasMoreSection = hasFilteredOptions && hasMore;

  return (
    <Popover onOpenChange={openChangeHandler} open={open} modal>
      <PopoverTrigger asChild>
        <Button
          className={cn("justify-between", widthClass)}
          variant="outline"
          ref={onChangeRef}
        >
          {title ? (
            <div className="truncate">{title}</div>
          ) : (
            <div className="truncate text-light-slate">{placeholder}</div>
          )}

          <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        style={
          width
            ? {
                width: `${width}px`,
              }
            : {}
        }
        className={cn("p-1 relative pt-12", hasMoreSection && "pb-10")}
        hideWhenDetached
      >
        <div className="absolute inset-x-1 top-0 h-12">
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder="Search"
            variant="ghost"
          ></SearchInput>
          <Separator className="mt-1" />
        </div>
        <div className="max-h-[40vh] overflow-y-auto">
          {isLoading && (
            <div className="flex items-center justify-center">
              <Spinner />
            </div>
          )}
          {hasFilteredOptions ? (
            filteredOptions.map((option) => (
              <div
                key={option.value}
                className="flex h-10 cursor-pointer items-center justify-between gap-2 rounded-md px-4 hover:bg-primary-foreground"
                onClick={() => {
                  onChange && onChange(option.value);
                  setOpen(false);
                }}
              >
                <div className="comet-body-s truncate">{option.label}</div>
                {option.value === value && (
                  <Check className="size-3 shrink-0" strokeWidth="3" />
                )}
              </div>
            ))
          ) : (
            <NoOptions text={noDataText} onLoadMore={onLoadMore} />
          )}
        </div>

        {hasMoreSection && (
          <div className="absolute inset-x-0 bottom-0 flex h-10 items-center justify-between px-4">
            <div className="comet-body-s text-muted-slate">
              {`Showing first ${optionsCount} items.`}
            </div>
            <Button variant="link" onClick={onLoadMore}>
              Load more
            </Button>
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
};

export default LoadableSelectBox;
