import React from "react";
import { Filter, FilterOperator, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import {
  DEFAULT_OPERATORS,
  NO_VALUE_OPERATORS,
  OPERATORS_MAP,
} from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";
import { cn } from "@/lib/utils";

type DictionaryRowProps = {
  config?: FilterRowConfig;
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const DictionaryRow: React.FunctionComponent<DictionaryRowProps> = ({
  config,
  filter,
  onChange,
}) => {
  const type: "string" | "number" =
    filter.type === COLUMN_TYPE.dictionary ? "string" : "number";

  const noInput = NO_VALUE_OPERATORS.includes(
    filter.operator as FilterOperator,
  );

  const keyValueChangeHandler = (value: unknown) =>
    onChange({ ...filter, key: value as string });

  const KeyComponent = config?.keyComponent ?? DebounceInput;

  const operatorSelector = (
    <OperatorSelector
      operator={filter.operator}
      operators={
        config?.operators ??
        OPERATORS_MAP[filter.type as COLUMN_TYPE] ??
        DEFAULT_OPERATORS
      }
      onSelect={(o) => onChange({ ...filter, operator: o })}
    />
  );

  return (
    <>
      <td className="flex gap-2 p-1">
        <KeyComponent
          className="w-full min-w-32 max-w-[30vw]"
          placeholder="key"
          value={filter.key}
          onValueChange={keyValueChangeHandler}
          data-testid="filter-dictionary-key-input"
          {...(config?.keyComponentProps ?? {})}
        />
        {!noInput && (
          <div className={cn(noInput ? "min-w-48" : "max-w-32")}>
            {operatorSelector}
          </div>
        )}
      </td>
      <td className="p-1">
        {noInput ? (
          operatorSelector
        ) : (
          <DebounceInput
            className="w-full min-w-40"
            placeholder="value"
            value={filter.value}
            onValueChange={(value) =>
              onChange({ ...filter, value: value as string })
            }
            disabled={filter.operator === ""}
            type={type}
            data-testid="filter-dictionary-value-input"
          />
        )}
      </td>
    </>
  );
};

export default DictionaryRow;
