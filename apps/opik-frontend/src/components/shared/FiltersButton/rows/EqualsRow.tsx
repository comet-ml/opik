import React from "react";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import SelectBox from "../../SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";

type EqualsRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
  placeholder: string;
  options: DropdownOption<string>[];
};

export const EqualsRow: React.FunctionComponent<EqualsRowProps> = ({
  filter,
  onChange,
  placeholder,
  options,
}) => {
  const value = `${filter.value}`;
  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={[{ value: "=", label: "=" }]}
          disabled
        />
      </td>
      <td className="p-1">
        <SelectBox
          value={value}
          options={options}
          placeholder={value || placeholder}
          onChange={(value) => onChange({ ...filter, value })}
        />
      </td>
    </>
  );
};

export default EqualsRow;
