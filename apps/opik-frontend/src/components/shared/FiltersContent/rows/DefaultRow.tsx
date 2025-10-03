import React from "react";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import { DEFAULT_OPERATORS } from "@/constants/filters";
import { Input } from "@/components/ui/input";

type DefaultRowProps = {
  filter: Filter;
};

export const DefaultRow: React.FunctionComponent<DefaultRowProps> = ({
  filter,
}) => {
  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={DEFAULT_OPERATORS}
          disabled
        />
      </td>
      <td className="p-1">
        <Input className="w-full min-w-40" placeholder="value" disabled />
      </td>
    </>
  );
};

export default DefaultRow;
