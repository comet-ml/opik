import React, { useCallback, useMemo } from "react";
import filter from "lodash/filter";
import uniq from "lodash/uniq";
import { Filter, FilterRowConfig, Filters } from "@/types/filters";
import { ColumnData, OnChangeFn } from "@/types/shared";
import FilterRow from "@/components/shared/FiltersContent/FilterRow";
import { cn } from "@/lib/utils";

type FiltersContentProps<TColumnData> = {
  filters: Filters;
  setFilters: OnChangeFn<Filters>;
  columns: ColumnData<TColumnData>[];
  config?: { rowsMap: Record<string, FilterRowConfig> };
  className?: string;
};

const FiltersContent = <TColumnData,>({
  filters,
  setFilters,
  columns,
  config,
  className,
}: FiltersContentProps<TColumnData>) => {
  const onRemoveRow = useCallback(
    (id: string) => {
      setFilters((prev) => filter(prev, (f) => f.id !== id));
    },
    [setFilters],
  );

  const onChangeRow = useCallback(
    (updateFilter: Filter) => {
      setFilters((prev) =>
        prev.map((f) => (updateFilter.id === f.id ? updateFilter : f)),
      );
    },
    [setFilters],
  );

  const disabledColumns = useMemo(() => {
    const disposableColumns = columns
      .filter((c) => c.disposable)
      .map((c) => c.id);

    if (!disposableColumns.length) {
      return undefined;
    }

    const columnsWithFilters = uniq(filters.map((f) => f.field));

    return disposableColumns.filter((c) => columnsWithFilters.includes(c));
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
    <div className={cn("overflow-y-auto overflow-x-hidden py-4", className)}>
      <table className="table-auto">
        <tbody>{renderFilters()}</tbody>
      </table>
    </div>
  );
};

export default FiltersContent;
