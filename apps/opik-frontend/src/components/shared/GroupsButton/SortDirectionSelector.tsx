import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { SORT_DIRECTION } from "@/types/sorting";
import { DropdownOption } from "@/types/shared";

export type SortDirectionSelectorProps = {
  direction: SORT_DIRECTION;
  onSelect?: (order: SORT_DIRECTION) => void;
  disabled?: boolean;
};

const SortDirectionSelector: React.FC<SortDirectionSelectorProps> = ({
  direction,
  onSelect,
  disabled,
}) => {
  const { t, i18n } = useTranslation();
  
  const OPTIONS: DropdownOption<SORT_DIRECTION>[] = useMemo(() => [
    { label: t("sorting.ascending"), value: SORT_DIRECTION.ASC },
    { label: t("sorting.descending"), value: SORT_DIRECTION.DESC },
  ], [t, i18n.language]);
  
  return (
    <SelectBox
      value={direction}
      options={OPTIONS}
      placeholder={t("sorting.direction")}
      onChange={onSelect as never}
      disabled={disabled}
    />
  );
};

export default SortDirectionSelector;
