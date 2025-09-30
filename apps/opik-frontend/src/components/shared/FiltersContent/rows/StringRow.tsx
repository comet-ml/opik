import React from "react";
import { Filter, FilterOperator, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import {
  DEFAULT_OPERATORS,
  OPERATORS_MAP,
  NO_VALUE_OPERATORS,
} from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";

type StringRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
  config?: FilterRowConfig;
};

export const StringRow: React.FunctionComponent<StringRowProps> = ({
  filter,
  onChange,
  config,
}) => {
  const KeyComponent = config?.keyComponent ?? DebounceInput;

  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            config?.operators ??
            OPERATORS_MAP[filter.type as COLUMN_TYPE] ??
            DEFAULT_OPERATORS
          }
          onSelect={(o) => onChange({ ...filter, operator: o })}
        />
      </td>
      <td className="p-1">
        {!NO_VALUE_OPERATORS.includes(filter.operator as FilterOperator) ? (
          <KeyComponent
            className="w-full min-w-40"
            placeholder="value"
            value={filter.value}
            onValueChange={(value) =>
              onChange({ ...filter, value: value as string })
            }
            disabled={filter.operator === ""}
            data-testid="filter-string-input"
            {...(config?.keyComponentProps ?? {})}
          />
        ) : null}
      </td>
    </>
  );
};

export default StringRow;
