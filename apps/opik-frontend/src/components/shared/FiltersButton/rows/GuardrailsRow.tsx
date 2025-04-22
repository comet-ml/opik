import React from "react";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersButton/OperatorSelector";
import SelectBox from "../../SelectBox/SelectBox";
import { GuardrailResult } from "@/types/guardrails";

type GuardrailsRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const GuardrailsRow: React.FunctionComponent<GuardrailsRowProps> = ({
  filter,
  onChange,
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
          options={[
            { value: GuardrailResult.FAILED, label: "Failed" },
            { value: GuardrailResult.PASSED, label: "Passed" },
          ]}
          placeholder={value || "Status"}
          onChange={(value) => onChange({ ...filter, value })}
        />
      </td>
    </>
  );
};

export default GuardrailsRow;
