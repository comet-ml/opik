import React from "react";
import { useTranslation } from "react-i18next";
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
  const { t } = useTranslation();
  
  return (
    <SelectBox
      value={operator}
      options={operators}
      placeholder={operator || t("filters.operator")}
      onChange={onSelect as never}
      disabled={disabled}
      testId="filter-operator"
    />
  );
};

export default OperatorSelector;
