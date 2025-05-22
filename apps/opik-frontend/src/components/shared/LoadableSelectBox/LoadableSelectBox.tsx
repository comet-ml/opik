import React, { ReactElement, useCallback, useMemo, useState } from "react";
import isFunction from "lodash/isFunction";
import toLower from "lodash/toLower";
import { Check, ChevronDown } from "lucide-react";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button, ButtonProps } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { DropdownOption } from "@/types/shared";
import NoOptions from "./NoOptions";
import SearchInput from "@/components/shared/SearchInput/SearchInput";

export type LoadableSelectBoxProps = {
  value?: string;
  placeholder?: ReactElement | string;
  onChange: (value: string) => void;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
  options: DropdownOption<string>[];
  variant?: "outline" | "ghost";
  optionsCount?: number;
  isLoading?: boolean;
  disabled?: boolean;
  onLoadMore?: () => void;
  buttonSize?: ButtonProps["size"];
  buttonClassName?: string;
  renderTitle?: (option: DropdownOption<string>) => ReactElement;
  actionPanel?: ReactElement;
  minWidth?: number;
};

export const LoadableSelectBox = ({
  value = "",
  placeholder = "Select value",
  onChange,
  open: controlledOpen,
  onOpenChange,
  options,
  buttonSize = "default",
  buttonClassName = "w-full",
  optionsCount = 25,
  isLoading = false,
  disabled,
  onLoadMore,
  renderTitle: parentRenderTitle,
  actionPanel,
  minWidth = 0,
}: LoadableSelectBoxProps) => {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [width, setWidth] = useState<number | undefined>();

  const isOpen =
    controlledOpen !== undefined ? controlledOpen : uncontrolledOpen;

  const hasMore = isFunction(onLoadMore);

  const noDataText = search
    ? hasMore
      ? `No search results for the first ${optionsCount} items`
      : "No search results"
    : "No data";

  const renderTitle = () => {
    const valueOption = options.find((o) => o.value === value);

    if (!valueOption) {
      return <div className="truncate text-light-slate">{placeholder}</div>;
    }

    if (isFunction(parentRenderTitle)) {
      return parentRenderTitle(valueOption);
    }

    return <div>{valueOption?.label}</div>;
  };

  const filteredOptions = useMemo(() => {
    return options.filter((o) => toLower(o.label).includes(toLower(search)));
  }, [options, search]);

  const openChangeHandler = useCallback(
    (open: boolean) => {
      if (!open) {
        setSearch("");
      }
      // Only update internal state if not controlled
      if (controlledOpen === undefined) {
        setUncontrolledOpen(open);
      }
      if (isFunction(onOpenChange)) {
        onOpenChange(open);
      }
    },
    [controlledOpen, onOpenChange],
  );

  const { ref } = useObserveResizeNode<HTMLButtonElement>((node) =>
    setWidth(node.clientWidth),
  );

  const hasFilteredOptions = Boolean(filteredOptions.length);
  const hasMoreSection = hasFilteredOptions && hasMore;
  const hasActionPanel = Boolean(actionPanel);
  const hasBottomActions = hasMoreSection || hasActionPanel;

  return (
    <Popover onOpenChange={openChangeHandler} open={isOpen} modal>
      <PopoverTrigger asChild>
        <Button
          className={cn("justify-between", buttonClassName)}
          size={buttonSize}
          variant="outline"
          disabled={disabled}
          ref={ref}
        >
          {renderTitle()}

          <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate" />
        </Button>
      </PopoverTrigger>
      <PopoverContent
        align="end"
        style={
          width
            ? {
                width: `${Math.max(width, minWidth)}px`,
              }
            : {}
        }
        className="relative p-1 pt-12"
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
                className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                onClick={() => {
                  onChange && onChange(option.value);
                  openChangeHandler(false);
                }}
              >
                <div className="min-w-4">
                  {option.value === value && (
                    <Check className="size-3.5 shrink-0" strokeWidth="3" />
                  )}
                </div>

                <div className="comet-body-s truncate">{option.label}</div>
              </div>
            ))
          ) : (
            <NoOptions text={noDataText} onLoadMore={onLoadMore} />
          )}
        </div>

        {hasBottomActions && (
          <div className="sticky inset-x-0 bottom-0">
            {hasMoreSection && (
              <div className="flex items-center justify-between px-4">
                <div className="comet-body-s text-muted-slate">
                  {`Showing first ${optionsCount} items.`}
                </div>
                <Button variant="link" onClick={onLoadMore}>
                  Load more
                </Button>
              </div>
            )}
            {hasActionPanel && actionPanel}
          </div>
        )}
      </PopoverContent>
    </Popover>
  );
};

export default LoadableSelectBox;
