import React from "react";
import { Filter, FilterOperator, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/shared/FiltersContent/OperatorSelector";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";
import {
  DEFAULT_OPERATORS,
  NO_VALUE_OPERATORS,
  OPERATORS_MAP,
} from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";
import { toString } from "@/lib/utils";

type ListRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
  config?: FilterRowConfig;
};

export const ListRow: React.FunctionComponent<ListRowProps> = ({
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
            value={toString(filter.value)}
            onValueChange={(value) =>
              onChange({ ...filter, value: value as string })
            }
            disabled={filter.operator === ""}
            data-testid="filter-list-input"
            {...(config?.keyComponentProps ?? {})}
          />
        ) : null}
      </td>
    </>
  );
};

export default ListRow;
