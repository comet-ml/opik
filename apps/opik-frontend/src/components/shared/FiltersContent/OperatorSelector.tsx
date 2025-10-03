import React from "react";
import { FilterOperator } from "@/types/filters";
import { DropdownOption } from "@/types/shared";
import SelectBox from "@/components/shared/SelectBox/SelectBox";

export type OperatorSelectorProps = {
  operator: FilterOperator | "";
  operators: DropdownOption<FilterOperator>[];
  onSelect?: (operator: FilterOperator) => void;
  disabled?: boolean;
};

const OperatorSelector: React.FunctionComponent<OperatorSelectorProps> = ({
  operator,
  operators,
  onSelect,
  disabled,
}) => {
  return (
    <SelectBox
      value={operator}
      options={operators}
      placeholder={operator || "Operator"}
      onChange={onSelect as never}
      disabled={disabled}
      testId="filter-operator"
    />
  );
};

export default OperatorSelector;
