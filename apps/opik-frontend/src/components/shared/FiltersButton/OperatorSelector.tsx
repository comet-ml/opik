import React, { useMemo } from "react";
import find from "lodash/find";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { FilterOperator } from "@/types/filters";
import { DropdownOption } from "@/types/shared";

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
  const selectedOperator = useMemo(() => {
    return find(operators, (o) => o.value === operator);
  }, [operator, operators]);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button className="w-full" variant="outline" disabled={disabled}>
          {selectedOperator?.label ?? (operator || "Operator")}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-52">
        {operators.map((option) => (
          <DropdownMenuCheckboxItem
            key={option.value}
            checked={option.value === operator}
            onSelect={() => {
              onSelect && onSelect(option.value);
            }}
          >
            {option.label}
          </DropdownMenuCheckboxItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default OperatorSelector;
