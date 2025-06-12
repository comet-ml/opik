import React, { useCallback, useEffect, useMemo, useState } from "react";
import filter from "lodash/filter";
import uniq from "lodash/uniq";
import { Filter as FilterIcon, Plus } from "lucide-react";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Filter, FilterRowConfig, Filters } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import { createEmptyFilter, isFilterValid } from "@/lib/filters";
import FilterRow from "@/components/shared/FiltersButton/FilterRow";
import useDeepMemo from "@/hooks/useDeepMemo";

type FilterButtonConfig = {
  rowsMap: Record<string, FilterRowConfig>;
};

type FiltersButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: FilterButtonConfig;
  filters: Filters;
  onChange: (filters: Filters) => void;
};

const FiltersButton = <TColumnData,>({
  filters: initialFilters,
  config,
  columns,
  onChange,
}: FiltersButtonProps<TColumnData>) => {
  const [filters, setFilters] = useState<Filters>(initialFilters);
  const [open, setOpen] = useState(false);

  const validFilters = useDeepMemo(() => {
    return filter(filters, isFilterValid);
  }, [filters]);

  useEffect(() => {
    if (!open) {
      if (initialFilters.length === 0) {
        setFilters([createEmptyFilter()]);
      } else {
        setFilters(initialFilters);
      }
    }
  }, [initialFilters, open]);

  useEffect(() => {
    return onChange(validFilters);
  }, [validFilters, onChange]);

  const clearHandler = useCallback(() => {
    setFilters([]);
  }, []);

  const addHandler = useCallback(() => {
    setFilters((state) => [...state, createEmptyFilter()]);
  }, []);

  const onRemoveRow = useCallback((id: string) => {
    setFilters((state) => filter(state, (f) => f.id !== id));
  }, []);

  const onChangeRow = useCallback((updateFilter: Filter) => {
    setFilters((state) =>
      state.map((f) => (updateFilter.id === f.id ? updateFilter : f)),
    );
  }, []);

  const disabledColumns = useMemo(() => {
    const disposableColumns = columns
      .filter((c) => c.disposable)
      .map((c) => c.id);

    if (!disposableColumns.length) {
      return undefined;
    }

    const columnsWIthFilters = uniq(filters.map((f) => f.field));

    return disposableColumns.filter((c) => columnsWIthFilters.includes(c));
  }, [filters, columns]);

  const getConfig = useCallback(
    (field: string) => config?.rowsMap[field],
    [config],
  );

  const renderFilters = () => {
    return filters.map((filter, index) => {
      const prefix = index === 0 ? "Where" : "And";

      return (
        <FilterRow
          key={filter.id}
          columns={columns}
          getConfig={getConfig}
          disabledColumns={disabledColumns}
          filter={filter}
          prefix={prefix}
          onRemove={onRemoveRow}
          onChange={onChangeRow}
        />
      );
    });
  };

  return (
    <Popover onOpenChange={setOpen} open={open}>
      <PopoverTrigger asChild>
        <Button variant="secondary" size="sm">
          <FilterIcon className="mr-2 size-3.5" />
          Filters
          {` (${validFilters.length})`}
        </Button>
      </PopoverTrigger>
      <PopoverContent className="min-w-[540px] px-8 py-6" align="start">
        <div className="flex flex-col gap-1">
          <div className="flex items-center justify-between pb-1">
            <span className="comet-title-s">Filters</span>
            <Button
              variant="ghost"
              size="sm"
              className="-mr-2.5"
              onClick={clearHandler}
            >
              Clear all
            </Button>
          </div>
          <Separator />
          <div className="-mr-1 max-h-[50vh] overflow-y-auto overflow-x-hidden py-4">
            <table className="table-auto">
              <tbody>{renderFilters()}</tbody>
            </table>
          </div>
          <div className="flex items-center">
            <Button variant="secondary" onClick={addHandler}>
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
