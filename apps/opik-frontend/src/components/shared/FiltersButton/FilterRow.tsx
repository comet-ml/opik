import React from "react";

import { X } from "lucide-react";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import ColumnSelector from "@/components/shared/FiltersButton/ColumnSelector";
import { Button } from "@/components/ui/button";
import { DEFAULT_OPERATOR_MAP, OPERATORS_MAP } from "@/constants/filters";
import StringRow from "@/components/shared/FiltersButton/rows/StringRow";
import NumberRow from "@/components/shared/FiltersButton/rows/NumberRow";
import ListRow from "@/components/shared/FiltersButton/rows/ListRow";
import TimeRow from "@/components/shared/FiltersButton/rows/TimeRow";
import DictionaryRow, {
  DictionaryRowConfig,
} from "@/components/shared/FiltersButton/rows/DictionaryRow";
import DefaultRow from "@/components/shared/FiltersButton/rows/DefaultRow";
import { createEmptyFilter } from "@/lib/filters";
import GuardrailsRow from "./rows/GuardrailsRow";

export type FilterRowConfig = DictionaryRowConfig;

type FilterRowProps<TColumnData> = {
  prefix: string;
  columns: ColumnData<TColumnData>[];
  config?: FilterRowConfig;
  filter: Filter;
  onRemove: (id: string) => void;
  onChange: (filter: Filter) => void;
};

export const FilterRow = <TColumnData,>({
  filter,
  columns,
  prefix,
  config,
  onRemove,
  onChange,
}: FilterRowProps<TColumnData>) => {
  const renderByType = () => {
    switch (filter.type) {
      case COLUMN_TYPE.string:
        return <StringRow filter={filter} onChange={onChange} />;
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
      case COLUMN_TYPE.guardrails:
        return <GuardrailsRow filter={filter} onChange={onChange} />;
      case "":
      default:
        return <DefaultRow filter={filter} />;
    }
  };

  return (
    <tr>
      <td className="comet-body-s p-1">{prefix}</td>
      <td className="p-1">
        <ColumnSelector
          columns={columns}
          field={filter.field}
          onSelect={(column) =>
            onChange({
              ...createEmptyFilter(),
              id: filter.id,
              field: column.id,
              type: column.type as COLUMN_TYPE,
              operator:
                DEFAULT_OPERATOR_MAP[column.type as COLUMN_TYPE] ??
                OPERATORS_MAP[column.type as COLUMN_TYPE]?.[0]?.value ??
                "",
            })
          }
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
  );
};

export default FilterRow;
