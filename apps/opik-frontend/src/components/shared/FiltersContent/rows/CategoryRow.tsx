import React from "react";
import { Filter, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { COLUMN_TYPE } from "@/types/shared";
import { DEFAULT_OPERATORS, OPERATORS_MAP } from "@/constants/filters";

type EqualsRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
  config?: FilterRowConfig;
};

export const CategoryRow: React.FunctionComponent<EqualsRowProps> = ({
  filter,
  onChange,
  config,
}) => {
  const value = `${filter.value}`;
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
          disabled
        />
      </td>
      <td className="p-1">
        <SelectBox
          value={value}
          options={[]}
          placeholder="Select value"
          onChange={(value) => onChange({ ...filter, value })}
          {...(config?.keyComponentProps ?? {})}
        />
      </td>
    </>
  );
};

export default CategoryRow;
