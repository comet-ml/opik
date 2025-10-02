import React, { useCallback, useEffect, useState } from "react";
import { Filter as FilterIcon, Plus } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button, ButtonProps } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { FilterRowConfig, Filters } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import { createFilter, isFilterValid } from "@/lib/filters";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";
import useDeepMemo from "@/hooks/useDeepMemo";
import { cn } from "@/lib/utils";

type FilterButtonConfig = {
  rowsMap: Record<string, FilterRowConfig>;
};

type FiltersButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: FilterButtonConfig;
  filters: Filters;
  onChange: (filters: Filters) => void;
  layout?: "standard" | "icon";
  variant?: ButtonProps["variant"];
  align?: "start" | "end";
  disabled?: boolean;
};

const FiltersButton = <TColumnData,>({
  filters: initialFilters,
  config,
  columns,
  onChange,
  layout = "standard",
  variant = "outline",
  align = "start",
  disabled,
}: FiltersButtonProps<TColumnData>) => {
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [open, setOpen] = useState(false);
  const isIconLayout = layout === "icon";

  const validFilters = useDeepMemo(() => {
    return filters.filter(isFilterValid);
  }, [filters]);

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
    onChange(validFilters);
  }, [validFilters, onChange]);

  return (
    <Popover onOpenChange={setOpen} open={open}>
      <PopoverTrigger asChild>
        <Button
          variant={variant}
          size="sm"
          className={cn(
            isIconLayout && !validFilters.length && "size-8 px-0",
            isIconLayout && validFilters.length && "px-3",
          )}
          disabled={disabled}
        >
          <FilterIcon className="size-3.5 shrink-0" />
          {isIconLayout ? (
            validFilters.length ? (
              <span className="ml-1.5">{validFilters.length}</span>
            ) : null
          ) : (
            <span className="ml-1.5">{`Filters (${validFilters.length})`}</span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="min-w-[540px] px-8 py-6" align={align}>
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
            className="-mr-1 max-h-[50vh]"
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
