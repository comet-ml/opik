import React from "react";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";
import { COLUMN_TYPE } from "@/types/shared";

export type DictionaryRowConfig = {
  keyComponent: React.FC<unknown> & {
    placeholder: string;
    value: string;
    onValueChange: (value: string) => void;
  };
  keyComponentProps: unknown;
};

type DictionaryRowProps = {
  config?: DictionaryRowConfig;
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

  const keyValueChangeHandler = (value: unknown) =>
    onChange({ ...filter, key: value as string });

  const KeyComponent = config?.keyComponent ?? DebounceInput;

  return (
    <>
      <td className="flex gap-2 p-1">
        <KeyComponent
          className="w-full min-w-32"
          placeholder="key"
          value={filter.key}
          onValueChange={keyValueChangeHandler}
          data-testid="filter-dictionary-key-input"
          {...(config?.keyComponentProps ?? {})}
        />
        <div className="max-w-32">
          <OperatorSelector
            operator={filter.operator}
            operators={
              OPERATORS_MAP[filter.type as COLUMN_TYPE] ?? DEFAULT_OPERATORS
            }
            onSelect={(o) => onChange({ ...filter, operator: o })}
          />
        </div>
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
          type={type}
          data-testid="filter-dictionary-value-input"
        />
      </td>
    </>
  );
};

export default DictionaryRow;
