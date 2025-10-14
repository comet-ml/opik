import React, { ReactElement, useCallback, useMemo, useState } from "react";
import isFunction from "lodash/isFunction";
import toLower from "lodash/toLower";
import isArray from "lodash/isArray";
import { Check, ChevronDown } from "lucide-react";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button, ButtonProps } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { Separator } from "@/components/ui/separator";
import { Checkbox } from "@/components/ui/checkbox";
import { cn } from "@/lib/utils";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { DropdownOption } from "@/types/shared";
import NoOptions from "./NoOptions";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface BaseLoadableSelectBoxProps {
  placeholder?: ReactElement | string;
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
  actionPanel?: ReactElement;
  minWidth?: number;
}

interface SingleSelectProps extends BaseLoadableSelectBoxProps {
  value?: string;
  onChange: (value: string) => void;
  multiselect?: false;
  renderTitle?: (option: DropdownOption<string>) => ReactElement;
}

interface MultiSelectProps extends BaseLoadableSelectBoxProps {
  value?: string[];
  onChange: (value: string[]) => void;
  multiselect: true;
  renderTitle?: (option: DropdownOption<string>[]) => ReactElement;
  showSelectAll?: boolean;
  selectAllLabel?: string;
}

export type LoadableSelectBoxProps = SingleSelectProps | MultiSelectProps;

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
  multiselect = false,
  ...props
}: LoadableSelectBoxProps) => {
  const showSelectAll =
    multiselect && "showSelectAll" in props ? props.showSelectAll : false;
  const selectAllLabel =
    multiselect && "selectAllLabel" in props && props.selectAllLabel
      ? props.selectAllLabel
      : "All selected";
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

  const selectedValues = useMemo(() => {
    return multiselect && isArray(value) ? value : [];
  }, [multiselect, value]);

  const isSelected = useCallback(
    (optionValue: string) => {
      return multiselect
        ? selectedValues.includes(optionValue)
        : value === optionValue;
    },
    [multiselect, selectedValues, value],
  );

  const selectedOptions = useMemo(
    () => options.filter((o) => isSelected(o.value)),
    [isSelected, options],
  );

  const titleText = useMemo(() => {
    if (
      multiselect &&
      selectedOptions.length === options.length &&
      selectAllLabel
    ) {
      return selectAllLabel;
    }

    return selectedOptions.map((o) => o.label).join(", ");
  }, [multiselect, options.length, selectAllLabel, selectedOptions]);

  const renderTitle = () => {
    if (!selectedOptions.length) {
      return <div className="truncate text-light-slate">{placeholder}</div>;
    }

    if (isFunction(parentRenderTitle)) {
      return multiselect
        ? (
            parentRenderTitle as (
              option: DropdownOption<string>[],
            ) => ReactElement
          )(selectedOptions)
        : (
            parentRenderTitle as (
              option: DropdownOption<string>,
            ) => ReactElement
          )(selectedOptions[0]);
    }

    return <div className="truncate">{titleText}</div>;
  };

  const filteredOptions = useMemo(() => {
    return options.filter((o) => toLower(o.label).includes(toLower(search)));
  }, [options, search]);

  const allFilteredSelected = useMemo(() => {
    if (!multiselect || !filteredOptions.length) return false;
    return filteredOptions.every((option) =>
      selectedValues.includes(option.value),
    );
  }, [multiselect, filteredOptions, selectedValues]);

  const handleSelectAll = useCallback(() => {
    if (!multiselect) return;

    if (allFilteredSelected) {
      const filteredValues = filteredOptions.map((o) => o.value);
      const newSelectedValues = selectedValues.filter(
        (v) => !filteredValues.includes(v),
      );
      onChange && (onChange as (value: string[]) => void)(newSelectedValues);
    } else {
      const newSelectedValues = [
        ...new Set([...selectedValues, ...filteredOptions.map((o) => o.value)]),
      ];
      onChange && (onChange as (value: string[]) => void)(newSelectedValues);
    }
  }, [
    multiselect,
    allFilteredSelected,
    filteredOptions,
    selectedValues,
    onChange,
  ]);

  const openChangeHandler = useCallback(
    (open: boolean) => {
      if (!open) {
        setSearch("");
      }

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

  const tooltipContent = useMemo(() => {
    if (!multiselect || !selectedValues.length || isOpen) return null;

    return titleText;
  }, [multiselect, selectedValues.length, titleText, isOpen]);

  const buttonElement = (
    <Button
      className={cn("justify-between", buttonClassName, {
        "disabled:cursor-not-allowed disabled:border-input disabled:bg-muted-disabled disabled:text-muted-gray disabled:placeholder:text-muted-gray hover:disabled:shadow-none":
          disabled,
      })}
      size={buttonSize}
      variant="outline"
      disabled={disabled}
      ref={ref}
      type="button"
    >
      {renderTitle()}

      <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate" />
    </Button>
  );

  return (
    <Popover onOpenChange={openChangeHandler} open={isOpen} modal>
      {multiselect && tooltipContent ? (
        <TooltipWrapper content={tooltipContent}>
          <PopoverTrigger asChild>{buttonElement}</PopoverTrigger>
        </TooltipWrapper>
      ) : (
        <PopoverTrigger asChild>{buttonElement}</PopoverTrigger>
      )}
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
        onCloseAutoFocus={(e) => e.preventDefault()}
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
        <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
          {isLoading && (
            <div className="flex items-center justify-center">
              <Spinner />
            </div>
          )}
          {hasFilteredOptions ? (
            <>
              {filteredOptions.map((option) => (
                <div
                  key={option.value}
                  className={cn(
                    "flex cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground",
                    option.description ? "min-h-12 py-2" : "h-10",
                  )}
                  onClick={() => {
                    if (multiselect) {
                      const newSelectedValues = isSelected(option.value)
                        ? selectedValues.filter((v) => v !== option.value)
                        : [...selectedValues, option.value];
                      onChange &&
                        (onChange as (value: string[]) => void)(
                          newSelectedValues,
                        );
                    } else {
                      onChange &&
                        (onChange as (value: string) => void)(option.value);
                      openChangeHandler(false);
                    }
                  }}
                >
                  {multiselect ? (
                    <Checkbox
                      checked={isSelected(option.value)}
                      className="shrink-0"
                    />
                  ) : (
                    <div className="min-w-4">
                      {isSelected(option.value) && (
                        <Check className="size-3.5 shrink-0" strokeWidth="3" />
                      )}
                    </div>
                  )}

                  <div className="min-w-0 flex-1">
                    <div className="comet-body-s truncate">{option.label}</div>
                    {option.description && (
                      <div className="comet-body-xs truncate text-muted-foreground">
                        {option.description}
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </>
          ) : (
            <NoOptions text={noDataText} onLoadMore={onLoadMore} />
          )}
        </div>

        {(showSelectAll && hasFilteredOptions) || hasBottomActions ? (
          <div className="sticky inset-x-0 bottom-0">
            {showSelectAll && hasFilteredOptions && (
              <>
                <Separator className="my-1" />
                <div
                  className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                  onClick={handleSelectAll}
                >
                  <Checkbox
                    checked={allFilteredSelected}
                    className="shrink-0"
                  />
                  <div className="min-w-0 flex-1">
                    <div className="comet-body-s truncate">Select all</div>
                  </div>
                </div>
              </>
            )}
            {hasMoreSection && (
              <div className="flex flex-wrap items-center justify-between border-t border-border px-4">
                <div className="comet-body-s text-light-slate">
                  {`Showing first ${optionsCount} items.`}
                </div>
                <Button variant="link" onClick={onLoadMore} type="button">
                  Load more
                </Button>
              </div>
            )}
            {hasActionPanel && actionPanel}
          </div>
        ) : null}
      </PopoverContent>
    </Popover>
  );
};

export default LoadableSelectBox;
