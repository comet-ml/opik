import React from "react";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";

type StringRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const StringRow: React.FunctionComponent<StringRowProps> = ({
  filter,
  onChange,
}) => {
  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            OPERATORS_MAP[filter.type as COLUMN_TYPE] ?? DEFAULT_OPERATORS
          }
          onSelect={(o) => onChange({ ...filter, operator: o })}
        />
      </td>
      <td className="p-1">
        <DebounceInput
          className="w-full min-w-40"
          placeholder="value"
          value={filter.value}
          onValueChange={(value) =>
            onChange({ ...filter, value: value as string })
          }
          disabled={filter.operator === ""}
          data-testid="filter-string-input"
        />
      </td>
    </>
  );
};

export default StringRow;
