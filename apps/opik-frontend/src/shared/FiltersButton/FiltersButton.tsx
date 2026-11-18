import React, { useCallback, useEffect, useState } from "react";
import { Filter as FilterIcon, Plus } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Button, ButtonProps } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { FilterRowConfig, Filters } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import { createFilter, isFilterValid } from "@/lib/filters";
import FiltersContent from "@/shared/FiltersContent/FiltersContent";
import useDeepMemo from "@/hooks/useDeepMemo";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FilterButtonConfig = {
  rowsMap: Record<string, FilterRowConfig>;
};

type FiltersButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: FilterButtonConfig;
  filters: Filters;
  onChange: (filters: Filters) => void;
  layout?: "standard" | "icon" | "add";
  variant?: ButtonProps["variant"];
  size?: ButtonProps["size"];
  align?: "start" | "end";
  disabled?: boolean;
  deferOnChange?: boolean;
  tooltip?: string;
  className?: string;
};

const FiltersButton = <TColumnData,>({
  filters: initialFilters,
  config,
  columns,
  onChange,
  layout = "standard",
  variant = "outline",
  size,
  align = "start",
  disabled,
  deferOnChange = false,
  tooltip,
  className,
}: FiltersButtonProps<TColumnData>) => {
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [open, setOpen] = useState(false);

  const validFilters = useDeepMemo(() => {
    return filters.filter(isFilterValid);
  }, [filters]);

  const showAddChip = layout === "add" && validFilters.length === 0;
  // Once a filter is applied, the "add" layout falls back to the icon-with-count state.
  const isIconLayout = layout === "icon" || layout === "add";
  const buttonSize = size ?? (isIconLayout ? "icon-sm" : "sm");

  const onClearAll = useCallback(() => {
    setFilters([]);
  }, [setFilters]);

  const onAddFilter = useCallback(() => {
    setFilters((prev) => [...prev, createFilter()]);
  }, [setFilters]);

  useEffect(() => {
    if (!open) {
      if (initialFilters.length === 0) {
        setFilters([createFilter()]);
      } else {
        setFilters(initialFilters);
      }
    }
  }, [initialFilters, open]);

  useEffect(() => {
    if (!deferOnChange) {
      onChange(validFilters);
    }
  }, [validFilters, onChange, deferOnChange]);

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (deferOnChange && !isOpen) {
        onChange(filters.filter(isFilterValid));
      }
      setOpen(isOpen);
    },
    [deferOnChange, onChange, filters],
  );

  return (
    <Popover onOpenChange={handleOpenChange} open={open}>
      <TooltipWrapper
        content={
          isIconLayout && !showAddChip ? (tooltip ?? "Filters") : undefined
        }
      >
        <PopoverTrigger asChild>
          {showAddChip ? (
            <Button
              variant="outline"
              size="2xs"
              className={cn(
                "gap-1 rounded-full pl-1.5 pr-2 text-muted-slate",
                className,
              )}
              disabled={disabled}
            >
              <Plus className="size-3 shrink-0" />
              Add filter
            </Button>
          ) : (
            <Button
              variant={variant}
              size={buttonSize}
              className={cn(
                isIconLayout && validFilters.length && "w-auto px-3",
                className,
              )}
              disabled={disabled}
            >
              <FilterIcon
                className={cn("shrink-0", !isIconLayout && "size-3.5")}
              />
              {isIconLayout ? (
                validFilters.length ? (
                  <span className="ml-1.5">{validFilters.length}</span>
                ) : null
              ) : (
                <span className="ml-1.5">{`Filters (${validFilters.length})`}</span>
              )}
            </Button>
          )}
        </PopoverTrigger>
      </TooltipWrapper>
      <PopoverContent
        className="max-h-[70vh] min-w-[540px] px-8 py-6"
        align={align}
      >
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between pb-1">
            <span className="comet-title-s">Filters</span>
            <Button
              variant="ghost"
              size="sm"
              className="-mr-2.5"
              onClick={onClearAll}
            >
              Clear all
            </Button>
          </div>
          <Separator />
          <FiltersContent<TColumnData>
            filters={filters}
            setFilters={setFilters}
            columns={columns}
            config={config}
            className="-mr-1 max-h-[30vh]"
          />
          <div className="flex items-center">
            <Button variant="secondary" onClick={onAddFilter}>
              <Plus className="mr-2 size-4" />
              Add filter
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default FiltersButton;
