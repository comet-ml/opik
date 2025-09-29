import React, { useCallback } from "react";
import { X } from "lucide-react";
import isFunction from "lodash/isFunction";

import { Filter, FilterRowConfig } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import ColumnSelector from "@/components/shared/ColumnSelector/ColumnSelector";
import { Button } from "@/components/ui/button";
import { DEFAULT_OPERATOR_MAP, OPERATORS_MAP } from "@/constants/filters";
import StringRow from "@/components/shared/FiltersContent/rows/StringRow";
import NumberRow from "@/components/shared/FiltersContent/rows/NumberRow";
import ListRow from "@/components/shared/FiltersContent/rows/ListRow";
import TimeRow from "@/components/shared/FiltersContent/rows/TimeRow";
import DictionaryRow from "@/components/shared/FiltersContent/rows/DictionaryRow";
import DefaultRow from "@/components/shared/FiltersContent/rows/DefaultRow";
import CategoryRow from "@/components/shared/FiltersContent/rows/CategoryRow";
import { createFilter } from "@/lib/filters";

type FilterRowProps<TColumnData> = {
  prefix: string;
  columns: ColumnData<TColumnData>[];
  getConfig?: (field: string) => FilterRowConfig | undefined;
  disabledColumns?: string[];
  filter: Filter;
  onRemove: (id: string) => void;
  onChange: (filter: Filter) => void;
};

export const FilterRow = <TColumnData,>({
  filter,
  columns,
  prefix,
  getConfig,
  disabledColumns,
  onRemove,
  onChange: onFilterChange,
}: FilterRowProps<TColumnData>) => {
  const onChange = useCallback(
    (newFilter: Filter) => {
      const config = getConfig?.(newFilter.field);

      if (isFunction(config?.validateFilter)) {
        return onFilterChange({
          ...newFilter,
          error: config.validateFilter(newFilter),
        });
      }

      return onFilterChange(newFilter);
    },
    [getConfig, onFilterChange],
  );

  const renderByType = () => {
    const config = getConfig?.(filter.field);

    switch (filter.type) {
      case COLUMN_TYPE.string:
      case COLUMN_TYPE.errors:
        return (
          <StringRow filter={filter} onChange={onChange} config={config} />
        );
      case COLUMN_TYPE.duration:
      case COLUMN_TYPE.cost:
      case COLUMN_TYPE.number:
        return <NumberRow filter={filter} onChange={onChange} />;
      case COLUMN_TYPE.list:
        return <ListRow filter={filter} onChange={onChange} />;
      case COLUMN_TYPE.time:
        return <TimeRow filter={filter} onChange={onChange} />;
      case COLUMN_TYPE.dictionary:
      case COLUMN_TYPE.numberDictionary:
        return (
          <DictionaryRow filter={filter} onChange={onChange} config={config} />
        );
      case COLUMN_TYPE.category:
        return (
          <CategoryRow filter={filter} onChange={onChange} config={config} />
        );
      case "":
      default:
        return <DefaultRow filter={filter} />;
    }
  };

  return (
    <>
      <tr>
        <td className="comet-body-s p-1">{prefix}</td>
        <td className="p-1">
          <ColumnSelector
            columns={columns}
            field={filter.field}
            onSelect={(column) =>
              onChange({
                ...createFilter(),
                id: filter.id,
                field: column.id,
                type: column.type as COLUMN_TYPE,
                operator:
                  getConfig?.(column.id)?.defaultOperator ??
                  DEFAULT_OPERATOR_MAP[column.type as COLUMN_TYPE] ??
                  OPERATORS_MAP[column.type as COLUMN_TYPE]?.[0]?.value ??
                  "",
              })
            }
            disabledColumns={disabledColumns}
          ></ColumnSelector>
        </td>
        {renderByType()}
        <td>
          <Button
            variant="minimal"
            size="icon-xs"
            onClick={() => onRemove(filter.id)}
          >
            <X />
          </Button>
        </td>
      </tr>
      {filter.error && (
        <tr>
          <td
            colSpan={5}
            className="comet-body-xs max-w-56 p-1 text-destructive"
          >
            {filter.error}
          </td>
        </tr>
      )}
    </>
  );
};

export default FilterRow;
