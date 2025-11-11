import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Filter, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/components/shared/FiltersContent/OperatorSelector";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { COLUMN_TYPE } from "@/types/shared";
import { getOperatorsMap } from "@/lib/filters-i18n";

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
  const { t, i18n } = useTranslation();
  const value = `${filter.value}`;
  
  const operatorsMap = useMemo(() => getOperatorsMap(t), [t, i18n.language]);

  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            config?.operators ??
            operatorsMap[filter.type as COLUMN_TYPE] ??
            []
          }
          disabled
        />
      </td>
      <td className="p-1">
        <SelectBox
          value={value}
          options={[]}
          placeholder={t("filters.selectValue")}
          onChange={(value) => onChange({ ...filter, value })}
          {...(config?.keyComponentProps ?? {})}
        />
      </td>
    </>
  );
};

export default CategoryRow;
