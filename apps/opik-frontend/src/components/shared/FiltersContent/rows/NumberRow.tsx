import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import DebounceInput from "@/components/shared/DebounceInput/DebounceInput";
import { COLUMN_TYPE } from "@/types/shared";
import { getOperatorsMap } from "@/lib/filters-i18n";

type NumberRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
};

export const NumberRow: React.FunctionComponent<NumberRowProps> = ({
  filter,
  onChange,
}) => {
  const { t, i18n } = useTranslation();
  
  const operatorsMap = useMemo(() => getOperatorsMap(t), [t, i18n.language]);

  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            operatorsMap[filter.type as COLUMN_TYPE] ?? []
          }
          onSelect={(o) => onChange({ ...filter, operator: o })}
        />
      </td>
      <td className="p-1">
        <DebounceInput
          className="w-full min-w-40"
          placeholder={t("filters.value")}
          value={filter.value}
          type="number"
          onValueChange={(value) =>
            onChange({ ...filter, value: value as number })
          }
          disabled={filter.operator === ""}
          data-testid="filter-number-input"
        />
      </td>
    </>
  );
};

export default NumberRow;
